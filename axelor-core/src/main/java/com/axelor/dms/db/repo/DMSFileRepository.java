/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2025 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.dms.db.repo;

import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.User;
import com.axelor.common.Inflector;
import com.axelor.common.StringUtils;
import com.axelor.db.EntityHelper;
import com.axelor.db.JpaRepository;
import com.axelor.db.JpaSecurity;
import com.axelor.db.JpaSecurity.AccessType;
import com.axelor.db.Model;
import com.axelor.db.annotations.Track;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.dms.db.DMSFile;
import com.axelor.dms.db.DMSFileTag;
import com.axelor.dms.db.DMSPermission;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.mail.db.MailMessage;
import com.axelor.mail.db.repo.MailMessageRepository;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaAttachment;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaAttachmentRepository;
import com.axelor.rpc.Resource;
import com.axelor.rpc.filter.Filter;
import com.axelor.rpc.filter.JPQLFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.persistence.PersistenceException;
import org.apache.shiro.authz.UnauthorizedException;

public class DMSFileRepository extends JpaRepository<DMSFile> {

    @Inject
    private MetaFiles metaFiles;

    @Inject
    private JpaSecurity security;

    @Inject
    private DMSPermissionRepository dmsPermissions;

    @Inject
    private MetaAttachmentRepository attachments;

    // Inject self-reference to get proxy for transactions
    @Inject
    private DMSFileRepository self;

    private static final Pattern previewSupportedPattern = Pattern.compile("\\b(?:pdf|image)\\b");

    public DMSFileRepository() {
        super(DMSFile.class);
    }

    /**
     * Configuration for defining custom folder structures in the DMS for specific entity types.
     */
    private static class FolderStructureConfig {
        /** The fully qualified class name of the entity this configuration applies to */
        final String entityClass;

        /** The hierarchical folder path to create */
        final String[] rootPath;

        /** Ordered list of field names to check for establishing parent-child relationships.
         *  Fields are checked in priority order until a valid relation is found */
        final String[] fieldPriority;

        FolderStructureConfig(String entityClass, String[] rootPath, String[] fieldPriority) {
            this.entityClass = entityClass;
            this.rootPath = rootPath;
            this.fieldPriority = fieldPriority;
        }
    }

    private static final FolderStructureConfig EMPLOYEE_CONFIG = new FolderStructureConfig(
            "com.axelor.apps.hr.db.Employee",
            new String[] { "Employee data" },
            new String[] { "employee", "manager", "managerUser" });

    private static final FolderStructureConfig PROJECT_CONFIG = new FolderStructureConfig(
            "com.axelor.apps.project.db.Project",
            new String[] { "Project Management", "Project Data" },
            new String[] { "project", "parentProject" });

    private static final FolderStructureConfig[] FOLDER_CONFIGS = {
            EMPLOYEE_CONFIG,
            PROJECT_CONFIG
    };

    /**
     * Saves a DMSFile, automatically organizing it into the appropriate folder structure
     * and managing attachments and permissions.
     *
     * @param entity the DMSFile to save
     * @return the saved DMSFile
     */
    @Override
    public DMSFile save(DMSFile entity) {
        DMSFile parent = entity.getParent();
        Model related = findRelated(entity);
        if (related == null && parent != null) {
            related = findRelated(parent);
        }

        final boolean isAttachment = related != null && entity.getMetaFile() != null;

        if (related != null) {
            entity.setRelatedId(related.getId());
            entity.setRelatedModel(related.getClass().getName());
        }

        // if new attachment, save attachment reference
        if (isAttachment) {
            // remove old attachment if file is moved
            MetaAttachment attachmentOld = attachments.all().filter("self.metaFile.id = ?", entity.getMetaFile().getId())
                    .fetchOne();
            if (attachmentOld != null) {
                attachments.remove(attachmentOld);
            }

            MetaAttachment attachment = attachments
                    .all()
                    .filter(
                            "self.metaFile.id = ? AND self.objectId = ? AND self.objectName = ?",
                            entity.getMetaFile().getId(),
                            related.getId(),
                            related.getClass().getName())
                    .fetchOne();
            if (attachment == null) {
                attachment = metaFiles.attach(entity.getMetaFile(), related);
                attachments.save(attachment);
            }

            // generate track message
            createMessage(entity, false);
        }

        // if not an attachment or has parent, do nothing
        if (parent == null && related != null) {
            // create parent folders
            final DMSFile dmsHome = findOrCreateHome(related);
            entity.setParent(dmsHome);
        }

        if (entity.getVersion() == null || entity.getVersion() == 0) {
            copyParentPermissions(entity);
        }

        DMSFile savedEntity = super.save(entity);
        return savedEntity;
    }

    /**
     * Removes a DMSFile and all its children, cleaning up associated MetaFiles and attachments.
     *
     * @param entity the DMSFile to remove
     */
    @Override
    public void remove(DMSFile entity) {
        // remove all children
        if (Boolean.TRUE.equals(entity.getIsDirectory())) {
            final List<DMSFile> children = all().filter("self.parent.id = ?", entity.getId()).fetch();
            for (DMSFile child : children) {
                if (child != entity) {
                    remove(child);
                }
            }
        }

        // remove attached file
        if (entity.getMetaFile() != null) {
            final MetaFile metaFile = entity.getMetaFile();
            long count = attachments.all().filter("self.metaFile = ?", metaFile).count();
            if (count == 1) {
                final MetaAttachment attachment = attachments.all().filter("self.metaFile = ?", metaFile).fetchOne();
                attachments.remove(attachment);
                // generate track message
                createMessage(entity, true);
            }
            count = all().filter("self.metaFile = ?", metaFile).count();
            if (count == 1) {
                entity.setMetaFile(null);
                try {
                    metaFiles.delete(metaFile);
                } catch (IOException e) {
                    throw new PersistenceException(e);
                }
            }
        }

        super.remove(entity);
    }

    /**
     * Validates that the user has permission to create or move a file.
     *
     * @param json the file data being validated
     * @param context the validation context
     * @return the validated json
     * @throws UnauthorizedException if the user lacks permission
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(Map<String, Object> json, Map<String, Object> context) {
        final DMSFile file = findFrom(json);
        final DMSFile parent = findFrom((Map<String, Object>) json.get("parent"));
        if (parent == null) {
            return json;
        }
        if (file != null && file.getParent() == parent) {
            return json;
        }

        // check whether user can create/move document here
        if (file == null && !canCreate(parent)) {
            throw new UnauthorizedException(I18n.get("You can't create document here."));
        }
        if (file != null && file.getParent() != parent && !canCreate(parent)) {
            throw new UnauthorizedException(I18n.get("You can't move document here."));
        }

        return json;
    }


    /**
     * Populates additional metadata for a DMSFile including permissions, icons, and type information.
     * Also syncs directory names with related entities if applicable.
     *
     * @param json the file data to populate
     * @param context the population context
     * @return the populated json with additional metadata
     */
    @Override
    public Map<String, Object> populate(Map<String, Object> json, Map<String, Object> context) {
        if (context != null && context.get("_populate") == Boolean.FALSE) {
            return json;
        }

        DMSFile file = findFrom(json);

        if (file == null) {
            return json;
        }

        // Sync directory name with related entity if it changed
        if (Boolean.TRUE.equals(file.getIsDirectory()) && file.getRelatedId() != null) {
            try {
                boolean wasUpdated = self.syncDirectoryNameWithEntity(file.getId());

                if (wasUpdated) {
                    file = find(file.getId());
                    json.put("fileName", file.getFileName());
                }
            } catch (Exception ignored) {
                // Don't fail the whole populate operation if it could not sync a directory name
            }
        }

        boolean isFile = !Boolean.TRUE.equals(file.getIsDirectory());
        LocalDateTime dt = file.getUpdatedOn();
        if (dt == null) {
            dt = file.getCreatedOn();
        }

        final User user = AuthUtils.getUser();
        final MetaFile metaFile = file.getMetaFile();

        boolean isCreator = file.getCreatedBy() == user;
        boolean hasSecurityPermission = security.isPermitted(AccessType.CREATE, DMSFile.class, file.getId());

        long fullPermissionsCount = 0;
        if (user != null) {
            fullPermissionsCount = dmsPermissions
                    .all()
                    .filter(
                            "self.file = ? AND self.value = 'FULL' AND (self.user = ? OR self.group = ?)",
                            file,
                            user,
                            user.getGroup())
                    .count();
        }

        boolean canShare = isCreator || hasSecurityPermission || fullPermissionsCount > 0;

        json.put("typeIcon", isFile ? "file-earmark" : "folder-fill");
        json.put("downloadIcon", "download");
        json.put("detailsIcon", "info-circle");
        json.put("canShare", canShare);

        boolean canWrite = canCreate(file);
        json.put("canWrite", canWrite);

        if (canOffline(file, user)) {
            json.put("offline", true);
        }

        json.put("createdBy", Resource.toMapCompact(file.getCreatedBy()));
        json.put("createdOn", file.getCreatedOn());
        json.put("updatedBy", Resource.toMapCompact(file.getUpdatedBy()));
        json.put("updatedOn", dt);

        if ("html".equals(file.getContentType())) {
            json.put("fileType", "text/html");
            json.put("contentType", "html");
            json.put("typeIcon", "file-earmark-text");
        }
        if ("spreadsheet".equals(file.getContentType())) {
            json.put("fileType", "text/json");
            json.put("contentType", "spreadsheet");
            json.put("typeIcon", "file-earmark-excel");
        }

        if (metaFile != null) {
            String fileType = metaFile.getFileType();
            json.put("fileType", fileType);
            json.put("typeIcon", metaFiles.fileTypeIcon(metaFile));
            json.put("metaFile.sizeText", metaFile.getSizeText());

            if (StringUtils.notBlank(fileType) && previewSupportedPattern.matcher(fileType).find()) {
                json.put("inlineUrl", String.format("ws/dms/inline/%d", file.getId()));
            }
        }

        if (file.getTags() != null) {
            final List<Object> tags = new ArrayList<>();
            int tagCount = 0;
            for (DMSFileTag tag : file.getTags()) {
                tags.add(Resource.toMap(tag, "id", "code", "name", "style"));
                tagCount++;
            }
            json.put("tags", tags);
        }

        return json;
    }

    /**
     * Finds or creates the appropriate home folder for an entity based on configured
     * relationships and folder structures.
     *
     * @param related the entity to find/create a home folder for
     * @return the home folder for the entity
     */
    protected DMSFile findOrCreateHome(Model related) {
        // Check if the related IS one of the configured entity types
        FolderStructureConfig matchingConfig = findMatchingConfig(related);
        if (matchingConfig != null) {
            DMSFile entityHome = findOrCreateEntityHome(related, matchingConfig);
            return entityHome;
        }

        // Check if this model has relationships to any configured entity types
        for (FolderStructureConfig config : FOLDER_CONFIGS) {
            Model relatedEntity = getRelatedEntity(related, config);
            if (relatedEntity != null) {
                return findOrCreateNestedEntityHome(related, relatedEntity, config);
            }
        }

        // Fallback to standard structure
        return findOrCreateStandardHome(related);
    }

    /**
     * Finds the home directory for a related entity, checking configured parent relationships
     * and creating type-based folder structure when parent entities exist.
     *
     * @param related the entity to find the home directory for
     * @return the home DMSFile directory, or null if not found
     */
    @Nullable
    public DMSFile findHomeByRelated(Model related) {
        // Check all configured entity relationships
        for (FolderStructureConfig config : FOLDER_CONFIGS) {
            Model relatedEntity = getRelatedEntity(related, config);
            if (relatedEntity != null) {
                DMSFile entityHome = findHomeByRelated(relatedEntity);
                if (entityHome != null) {
                    final Inflector inflector = Inflector.getInstance();
                    String typeFolderName = inflector.pluralize(inflector.humanize(related.getClass().getSimpleName()));

                    DMSFile typeFolder = all()
                            .filter(
                                    "COALESCE(self.isDirectory, FALSE) = TRUE "
                                            + "AND self.parent = :parent "
                                            + "AND self.fileName = :folderName "
                                            + "AND self.relatedModel = :model "
                                            + "AND COALESCE(self.relatedId, 0) = 0")
                            .bind("parent", entityHome)
                            .bind("folderName", typeFolderName)
                            .bind("model", related.getClass().getName())
                            .fetchOne();

                    if (typeFolder != null) {
                        return typeFolder;
                    }
                }
            }
        }

        // Standard query for models without special relationships
        DMSFile home = all()
                .filter(
                        "COALESCE(self.isDirectory, FALSE) = TRUE "
                                + "AND self.relatedId = :id "
                                + "AND self.relatedModel = :model "
                                + "AND self.parent.relatedModel = :model "
                                + "AND COALESCE(self.parent.relatedId, 0) = 0")
                .bind("id", related.getId())
                .bind("model", related.getClass().getName())
                .fetchOne();

        return home;
    }

    /**
     * Creates the standard two-level folder structure: /ModelType/RecordName/
     * Used when no special folder structure is configured for the entity type.
     *
     * @param related the entity to create standard structure for
     * @return the entity's home folder
     */
    private DMSFile findOrCreateStandardHome(Model related) {
        final List<Filter> dmsRootFilters = Lists.newArrayList(
                new JPQLFilter(
                        "COALESCE(self.isDirectory, FALSE) = TRUE "
                                + "AND self.relatedModel = :model "
                                + "AND COALESCE(self.relatedId, 0) = 0"));
        final DMSFile dmsRootParent = getRootParent(related);

        if (dmsRootParent == null) {
            // Explicitly enforce root-level if no specific root parent is defined
            // This ensures that when a file is uploaded with it's related model,
            // we should not confuse an employee's folder say "Files" with the newly
            // uploaded file's root say "Files (root)" as they have the same name
            dmsRootFilters.add(new JPQLFilter("self.parent IS NULL"));
        } else {
            // Original logic: enforce specific parent if defined
            dmsRootFilters.add(new JPQLFilter("self.parent = :rootParent"));
        }

        DMSFile dmsRoot = Filter.and(dmsRootFilters)
                .build(DMSFile.class)
                .bind("model", related.getClass().getName())
                .bind("rootParent", dmsRootParent)
                .fetchOne();

        if (dmsRoot == null) {
            final Inflector inflector = Inflector.getInstance();
            dmsRoot = new DMSFile();
            String rootName = inflector.pluralize(inflector.humanize(related.getClass().getSimpleName()));
            if ("Employees".equals(rootName)) {
                rootName += " data";
            }
            dmsRoot.setFileName(rootName);
            dmsRoot.setRelatedModel(related.getClass().getName());
            dmsRoot.setIsDirectory(true);
            dmsRoot.setParent(dmsRootParent);
            dmsRoot = save(dmsRoot);
        }

        DMSFile dmsHome = findHomeByRelated(related);

        if (dmsHome == null) {
            String homeName = null;

            final Mapper mapper = Mapper.of(related.getClass());
            homeName = mapper.getNameField().get(related).toString();
            if (homeName == null) {
                homeName = Strings.padStart("" + related.getId(), 5, '0');
            }

            dmsHome = new DMSFile();
            dmsHome.setFileName(homeName);
            dmsHome.setRelatedId(related.getId());
            dmsHome.setRelatedModel(related.getClass().getName());
            dmsHome.setParent(dmsRoot);
            dmsHome.setIsDirectory(true);
            dmsHome = save(dmsHome);

        }
        return dmsHome;
    }

    /**
     * Creates or finds the entity's home folder within the configured root path structure.
     * Uses the entity's name field or padded ID for the folder name.
     *
     * @param entity the entity to create a home folder for
     * @param config the folder structure configuration
     * @return the entity's home folder
     */
    private DMSFile findOrCreateEntityHome(Model entity, FolderStructureConfig config) {
        DMSFile rootFolder = findOrCreateRootPath(config);

        DMSFile entityHome = all()
                .filter(
                        "COALESCE(self.isDirectory, FALSE) = TRUE "
                                + "AND self.parent = :parent "
                                + "AND self.relatedModel = :model "
                                + "AND self.relatedId = :id")
                .bind("parent", rootFolder)
                .bind("model", config.entityClass)
                .bind("id", entity.getId())
                .fetchOne();

        if (entityHome == null) {
            String entityName = null;
            final Mapper mapper = Mapper.of(entity.getClass());
            Property nameField = mapper.getNameField();
            if (nameField != null) {
                Object nameValue = nameField.get(entity);
                if (nameValue != null) {
                    entityName = nameValue.toString();
                }
            }

            if (entityName == null) {
                entityName = Strings.padStart("" + entity.getId(), 5, '0');
            }

            entityHome = new DMSFile();
            entityHome.setFileName(entityName);
            entityHome.setRelatedId(entity.getId());
            entityHome.setRelatedModel(config.entityClass);
            entityHome.setParent(rootFolder);
            entityHome.setIsDirectory(true);
            entityHome = super.save(entityHome);
        }

        return entityHome;
    }

    /**
     * Creates or finds a type-based folder under a parent entity's home folder.
     * Creates structure like: /Projects/Project001/Tasks/
     *
     * @param related the entity that needs a nested home
     * @param parentEntity the parent entity to nest under
     * @param config the folder structure configuration for the parent
     * @return the type folder for the related entity
     */
    private DMSFile findOrCreateNestedEntityHome(Model related, Model parentEntity, FolderStructureConfig config) {
        DMSFile parentHome = findOrCreateEntityHome(parentEntity, config);

        final Inflector inflector = Inflector.getInstance();
        String typeFolderName = inflector.pluralize(inflector.humanize(related.getClass().getSimpleName()));


        DMSFile typeFolder = all()
                .filter(
                        "COALESCE(self.isDirectory, FALSE) = TRUE "
                                + "AND self.parent = :parent "
                                + "AND self.fileName = :folderName "
                                + "AND self.relatedModel = :model "
                                + "AND COALESCE(self.relatedId, 0) = 0")
                .bind("parent", parentHome)
                .bind("folderName", typeFolderName)
                .bind("model", related.getClass().getName())
                .fetchOne();

        if (typeFolder == null) {
            typeFolder = new DMSFile();
            typeFolder.setFileName(typeFolderName);
            typeFolder.setRelatedModel(related.getClass().getName());
            typeFolder.setIsDirectory(true);
            typeFolder.setParent(parentHome);
            typeFolder = super.save(typeFolder);
        }

        return typeFolder;
    }

    /**
     * Creates or finds the folder hierarchy defined in the configuration.
     * The last folder in the path is marked with the entity's relatedModel.
     *
     * @param config the folder structure configuration with the path to create
     * @return the final folder in the path hierarchy
     */
    private DMSFile findOrCreateRootPath(FolderStructureConfig config) {

        DMSFile currentParent = null;

        for (int i = 0; i < config.rootPath.length; i++) {
            String folderName = config.rootPath[i];
            boolean isLastInPath = i == config.rootPath.length - 1;


            DMSFile folder = all()
                    .filter(
                            "COALESCE(self.isDirectory, FALSE) = TRUE "
                                    + "AND self.fileName = :name "
                                    + (currentParent == null ? "AND self.parent IS NULL" : "AND self.parent = :parent")
                                    + (isLastInPath ? " AND self.relatedModel = :model AND COALESCE(self.relatedId, 0) = 0"
                                    : " AND self.relatedModel IS NULL"))
                    .bind("name", folderName)
                    .bind("parent", currentParent)
                    .bind("model", isLastInPath ? config.entityClass : null)
                    .fetchOne();

            if (folder == null) {
                folder = new DMSFile();
                folder.setFileName(folderName);
                folder.setIsDirectory(true);
                folder.setParent(currentParent);

                if (isLastInPath) {
                    folder.setRelatedModel(config.entityClass);
                }

                folder = super.save(folder);
            } else {
            }

            currentParent = folder;
        }

        return currentParent;
    }

    /**
     * Checks if the given model is of the specified entity type.
     *
     * @param related the model to check
     * @param config the folder structure configuration containing the target entity class
     * @return true if the model matches the configured entity type
     */
    private boolean isEntityType(Model related, FolderStructureConfig config) {
        if (related == null) {
            return false;
        }
        Class<?> entityClass = EntityHelper.getEntityClass(related);
        return config.entityClass.equals(entityClass.getName());
    }

    /**
     * Finds the configuration that matches the given model's entity type.
     *
     * @param related the model to check
     * @return the matching configuration, or null if none found
     */
    @Nullable
    private FolderStructureConfig findMatchingConfig(Model related) {
        if (related == null) {
            return null;
        }

        Class<?> entityClass = EntityHelper.getEntityClass(related);
        String entityClassName = entityClass.getName();

        for (FolderStructureConfig config : FOLDER_CONFIGS) {
            if (config.entityClass.equals(entityClassName)) {
                return config;
            }
        }

        return null;
    }

    /**
     * Finds a property in the model that references the configured entity type,
     * checking priority fields first, then falling back to any matching reference field.
     *
     * @param related the model to search for the property
     * @param config the folder structure configuration containing target entity and field priorities
     * @return the matching Property, or null if not found
     */
    @Nullable
    private Property findRelatedEntityField(Model related, FolderStructureConfig config) {
        final Mapper mapper = Mapper.of(EntityHelper.getEntityClass(related));

        // Search for fields in priority order
        for (String priorityFieldName : config.fieldPriority) {
            for (Property prop : mapper.getProperties()) {
                if (!prop.isReference() || prop.getTarget() == null || prop.isCollection()) {
                    continue;
                }

                if (config.entityClass.equals(prop.getTarget().getName())
                        && priorityFieldName.equalsIgnoreCase(prop.getName())) {
                    return prop;
                }
            }
        }

        // If no priority field found, look for any field of the target type
        for (Property prop : mapper.getProperties()) {
            if (!prop.isReference() || prop.getTarget() == null || prop.isCollection()) {
                continue;
            }

            if (config.entityClass.equals(prop.getTarget().getName())) {
                return prop;
            }
        }

        return null;
    }

    /**
     * Retrieves the related entity instance from the model using the configured field.
     *
     * @param related the model containing the relationship
     * @param config the folder structure configuration
     * @return the related entity instance, or null if not found
     */
    @Nullable
    @SuppressWarnings("all")
    private Model getRelatedEntity(Model related, FolderStructureConfig config) {
        Property field = findRelatedEntityField(related, config);
        if (field == null) {
            return null;
        }

        try {
            Object entityObj = field.get(related);
            if (entityObj == null) {
                return null;
            }

            Model entity = EntityHelper.getEntity((Model) entityObj);
            return entity;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Finds the home directory for a related entity, checking configured parent relationships
     * and creating type-based folder structure when parent entities exist.
     *
     * @param related the entity to find the home directory for
     * @return the home DMSFile directory, or null if not found
     */
    @SuppressWarnings("all")
    private Model findRelated(DMSFile file) {

        if (file == null || file.getRelatedId() == null || file.getRelatedModel() == null) {
            return null;
        }
        Class<? extends Model> klass = null;
        try {
            klass = (Class) Class.forName(file.getRelatedModel());
        } catch (Exception e) {
            return null;
        }
        final Model entity = JpaRepository.of(klass).find(file.getRelatedId()); // Proxy object
        final Model result = EntityHelper.getEntity(entity); // "Unproxy" the model as outside of this when commu
        return result;
    }


    /**
     * Moves an entity's DMS file to the correct home folder based on current relationships.
     * Cleans up empty folders after moving.
     *
     * @param entityId the ID of the entity
     * @param entityModel the fully qualified class name of the entity
     * @return true if the file was moved, false otherwise
     */
    @Transactional
    public boolean moveFileToCorrectHome(Long entityId, String entityModel) {
        try {
            // Re-fetch the entity with its current relationships
            @SuppressWarnings("unchecked")
            Class<? extends Model> klass = (Class<? extends Model>) Class.forName(entityModel);
            Model entity = JpaRepository.of(klass).find(entityId);

            if (entity == null) {
                return false;
            }

            // Get the dmsFile from the entity
            Mapper mapper = Mapper.of(entity.getClass());
            Property dmsFileProperty = mapper.getProperty("dmsFile");

            if (dmsFileProperty == null) {
                return false;
            }

            DMSFile dmsFile = (DMSFile) dmsFileProperty.get(entity);
            if (dmsFile == null) {
                return false;
            }

            DMSFile oldParent = dmsFile.getParent();

            // Calculate where this file SHOULD be based on current entity state
            DMSFile newParent = findOrCreateHome(entity);

            if (oldParent != null && oldParent.getId().equals(newParent.getId())) {
                return false;
            }

            dmsFile.setParent(newParent);
            save(dmsFile);

            // Clean up old parent if it's now empty
            if (oldParent != null) {
                cleanupEmptyFolderChain(oldParent);
            }

            return true;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Recursively cleans up empty folders, walking up the parent chain.
     * This prevents orphaned empty folder structures after moving files.
     *
     * @param folder the folder to check and potentially delete
     */
    private void cleanupEmptyFolderChain(DMSFile folder) {
        if (folder == null) {
            return;
        }


            // Check if folder is empty
            long childCount = all()
                    .filter("self.parent = :parent")
                    .bind("parent", folder)
                    .count();

            if (childCount == 0) {

                // Remember parent before deleting (for recursive cleanup)
                DMSFile parentFolder = folder.getParent();

                remove(folder);

                // Recursively clean up parent folders if they're also empty now
                if (parentFolder != null) {
                    cleanupEmptyFolderChain(parentFolder);
                }
            }

    }


    /**
     * Syncs a directory's name with its related entity's current name.
     * This ensures entity home folders  reflect current entity names.
     *
     * @param fileId the DMSFile ID to sync
     * @return true if the name was updated, false otherwise
     */
    @Transactional
    public boolean syncDirectoryNameWithEntity(Long fileId) {
        DMSFile file = find(fileId);
        if (file == null) {
            return false;
        }

        // Only process directories with related entities
        if (!Boolean.TRUE.equals(file.getIsDirectory())) {
            return false;
        }

        if (file.getRelatedId() == null || file.getRelatedModel() == null) {
            return false;
        }

        // Get the related entity
        Model relatedEntity = findRelated(file);
        if (relatedEntity == null) {
            return false;
        }

        // Get what the directory name SHOULD be
        String expectedName = getExpectedDirectoryName(relatedEntity);
        if (expectedName == null || expectedName.trim().isEmpty()) {
            return false;
        }

        // Check if name needs updating
        String currentName = file.getFileName();
        if (expectedName.equals(currentName)) {
            return false;
        }

        file.setFileName(expectedName);

        // Use super.save to avoid triggering DMS folder creation logic
        super.save(file);

        return true;
    }

    /**
     * Determines what a directory name SHOULD be based on its related entity.
     * Tries to use the entity's name field, falls back to padded ID.
     * This matches the logic in findOrCreateEntityHome() to ensure consistency.
     *
     * @param entity the related entity
     * @return the expected directory name
     */
    private String getExpectedDirectoryName(Model entity) {
        if (entity == null) {
            return null;
        }

            final Mapper mapper = Mapper.of(EntityHelper.getEntityClass(entity));
            Property nameField = mapper.getNameField();

            if (nameField != null) {
                Object nameValue = nameField.get(entity);
                if (nameValue != null) {
                    String name = nameValue.toString();
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }

        // Fallback: Use padded ID (same logic as in findOrCreateEntityHome)
        if (entity.getId() != null) {
            return Strings.padStart("" + entity.getId(), 5, '0');
        }

        return null;
    }

    /**
     * Copies permissions from the parent folder to this file.
     *
     * @param entity the file to copy permissions to
     */
    private void copyParentPermissions(DMSFile entity) {
        Optional.ofNullable(entity.getParent())
                .map(DMSFile::getPermissions)
                .ifPresent(
                        permissions -> {
                            permissions.stream()
                                    .map(permission -> dmsPermissions.copy(permission, false))
                                    .forEach(entity::addPermission);
                        });
    }

    /**
     * Creates a tracking message when a file is added or removed from a tracked entity.
     * Only creates messages for entities annotated with @Track(files=true).
     *
     * @param file the DMSFile being added or removed
     * @param delete true if file is being deleted, false if being added
     */
    private void createMessage(DMSFile file, boolean delete) {
        final Model related = findRelated(file);
        if (related == null || related.getId() == null || file.getMetaFile() == null) {
            return;
        }

        final Class<?> klass = EntityHelper.getEntityClass(related);
        final Track track = klass.getAnnotation(Track.class);
        if (track == null || !track.files()) {
            return;
        }

        final ObjectMapper objectMapper = Beans.get(ObjectMapper.class);
        final MailMessageRepository messages = Beans.get(MailMessageRepository.class);
        final MailMessage message = new MailMessage();

        message.setRelatedId(related.getId());
        message.setRelatedModel(klass.getName());
        message.setAuthor(AuthUtils.getUser());

        message.setSubject(delete ? I18n.get("File removed") : I18n.get("File added"));

        final Map<String, Object> json = new HashMap<>();
        final Map<String, Object> attrs = new HashMap<>();

        attrs.put("id", file.getMetaFile().getFileName());
        attrs.put("fileName", file.getFileName());
        attrs.put("fileIcon", metaFiles.fileTypeIcon(file.getMetaFile()));

        json.put("files", Arrays.asList(attrs));
        try {
            String body = objectMapper.writeValueAsString(json);
            message.setBody(body);
        } catch (JsonProcessingException ignored) {

        }

        messages.save(message);
    }

    /**
     * Checks if the user has permission to create files in the given parent folder.
     *
     * @param parent the parent folder to check
     * @return true if the user can create files in this folder
     */
    private boolean canCreate(DMSFile parent) {
        final User user = AuthUtils.getUser();
        final Group group = user.getGroup();

        if (parent.getCreatedBy() == user
                || security.hasRole("role.super")
                || security.hasRole("role.admin")) {
            return true;
        }

        // allow if the parent folder has no specific dms permissions
        long permissionCount = dmsPermissions.all().filter("self.file = :file").bind("file", parent).count();
        if (permissionCount == 0) {
            return true;
        }

        long writePermissionCount = dmsPermissions
                .all()
                .filter(
                        "self.file = :file AND self.permission.canWrite = true AND "
                                + "(self.user = :user OR self.group = :group)")
                .bind("file", parent)
                .bind("user", user)
                .bind("group", group)
                .autoFlush(false)
                .count();

        boolean result = writePermissionCount > 0;
        return result;
    }

    private boolean canOffline(DMSFile file, User user) {
        return !Boolean.TRUE.equals(file.getIsDirectory())
                && file.getMetaFile() != null
                && dmsPermissions
                .all()
                .filter("self.file = ? AND self.value = 'OFFLINE' AND self.user = ?", file, user)
                .count() > 0;
    }

    /**
     * Marks a file as available offline for the current user.
     *
     * @param file the file to mark
     * @param offline true to mark as offline, false to remove offline status
     * @return the updated file
     */
    @Transactional
    public DMSFile setOffline(DMSFile file, boolean offline) {
        Preconditions.checkNotNull(file, "file can't be null");

        // directory can't be marked as offline
        if (Boolean.TRUE.equals(file.getIsDirectory())) {
            return file;
        }

        final User user = AuthUtils.getUser();
        boolean canOffline = canOffline(file, user);

        if (offline == canOffline) {
            return file;
        }

        DMSPermission permission;

        if (offline) {
            permission = new DMSPermission();
            permission.setValue("OFFLINE");
            permission.setFile(file);
            permission.setUser(user);
            file.addPermission(permission);
        } else {
            permission = dmsPermissions
                    .all()
                    .filter("self.file = ? AND self.value = 'OFFLINE' AND self.user = ?", file, user)
                    .fetchOne();
            file.removePermission(permission);
            dmsPermissions.remove(permission);
        }

        return this.save(file);
    }

    public List<DMSFile> findOffline(int limit, int offset) {
        return all()
                .filter("self.permissions[].value = 'OFFLINE' AND self.permissions[].user = :user")
                .bind("user", AuthUtils.getUser())
                .fetch(limit, offset);
    }

    /**
     * Gets root parent folder
     *
     * @param related model
     * @return root parent folder
     */
    @Nullable
    protected DMSFile getRootParent(Model related) {
        return null;
    }

    private DMSFile findFrom(Map<String, Object> json) {
        if (json == null || json.get("id") == null) {
            return null;
        }
        final Long id = Longs.tryParse(json.get("id").toString());
        if (id == null) {
            return null;
        }
        return find(id);
    }






}