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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.JpaTest;
import com.axelor.TestingHelpers;
import com.axelor.db.Model;
import com.axelor.dms.db.DMSFile;
import com.google.inject.persist.Transactional;
import javax.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DMSFileRepositoryTest extends JpaTest {

    @Inject private DMSFileRepository dmsFiles;

    @BeforeEach
    @Transactional
    public void ensureAuth() {
        ensureAuth("admin", "admin");
    }

    @AfterAll
    static void tearDown() {
        TestingHelpers.logout();
    }

    // Root Path Creation Tests

    @Test
    @Transactional
    public void testFindOrCreateRootPath_EmployeeConfig() {
        try {
            java.lang.reflect.Field configField =
                    DMSFileRepository.class.getDeclaredField("EMPLOYEE_CONFIG");
            configField.setAccessible(true);
            Object employeeConfig = configField.get(null);

            java.lang.reflect.Method method =
                    DMSFileRepository.class.getDeclaredMethod(
                            "findOrCreateRootPath",
                            employeeConfig.getClass());
            method.setAccessible(true);

            DMSFile result = (DMSFile) method.invoke(dmsFiles, employeeConfig);

            assertNotNull(result, "Root path folder should be created");
            assertTrue(result.getIsDirectory(), "Should be a directory");
            assertEquals("Employee data", result.getFileName(), "Folder name should match config");
            assertEquals("com.axelor.apps.hr.db.Employee", result.getRelatedModel(),
                    "Should have Employee as related model");
            assertNull(result.getParent(), "Root folder should have no parent");

            DMSFile result2 = (DMSFile) method.invoke(dmsFiles, employeeConfig);
            assertEquals(result.getId(), result2.getId(),
                    "Should return existing folder, not create new one");

        } catch (Exception e) {
            throw new RuntimeException("Test failed due to reflection error", e);
        }
    }

    @Test
    @Transactional
    public void testFindOrCreateRootPath_ProjectConfig() {
        try {
            java.lang.reflect.Field configField =
                    DMSFileRepository.class.getDeclaredField("PROJECT_CONFIG");
            configField.setAccessible(true);
            Object projectConfig = configField.get(null);

            java.lang.reflect.Method method =
                    DMSFileRepository.class.getDeclaredMethod(
                            "findOrCreateRootPath",
                            projectConfig.getClass());
            method.setAccessible(true);

            DMSFile result = (DMSFile) method.invoke(dmsFiles, projectConfig);

            assertNotNull(result, "Root path folder should be created");
            assertTrue(result.getIsDirectory(), "Should be a directory");
            assertEquals("Project Data", result.getFileName(),
                    "Final folder name should be 'Project Data'");
            assertEquals("com.axelor.apps.project.db.Project", result.getRelatedModel(),
                    "Should have Project as related model");

            DMSFile parent = result.getParent();
            assertNotNull(parent, "Should have parent folder");
            assertEquals("Project Management", parent.getFileName(),
                    "Parent should be 'Project Management'");
            assertNull(parent.getRelatedModel(),
                    "Intermediate folders should not have relatedModel");
            assertNull(parent.getParent(), "Top level folder should have no parent");

        } catch (Exception e) {
            throw new RuntimeException("Test failed due to reflection error", e);
        }
    }

    @Test
    @Transactional
    public void testFindOrCreateRootPath_Idempotent() {
        try {
            java.lang.reflect.Field configField =
                    DMSFileRepository.class.getDeclaredField("EMPLOYEE_CONFIG");
            configField.setAccessible(true);
            Object employeeConfig = configField.get(null);

            java.lang.reflect.Method method =
                    DMSFileRepository.class.getDeclaredMethod(
                            "findOrCreateRootPath",
                            employeeConfig.getClass());
            method.setAccessible(true);

            DMSFile first = (DMSFile) method.invoke(dmsFiles, employeeConfig);
            DMSFile second = (DMSFile) method.invoke(dmsFiles, employeeConfig);
            DMSFile third = (DMSFile) method.invoke(dmsFiles, employeeConfig);

            assertEquals(first.getId(), second.getId(),
                    "Second call should return same folder");
            assertEquals(first.getId(), third.getId(),
                    "Third call should return same folder");

            long count = dmsFiles.all()
                    .filter("self.fileName = ?1 AND self.relatedModel = ?2",
                            "Employee data", "com.axelor.apps.hr.db.Employee")
                    .count();
            assertEquals(1L, count, "Should only have one 'Employee data' folder");

        } catch (Exception e) {
            throw new RuntimeException("Test failed due to reflection error", e);
        }
    }

    // Entity Home Creation Tests

    @Test
    @Transactional
    public void testFindOrCreateEntityHome_CreatesNewFolder() {
        try {
            java.lang.reflect.Field configField =
                    DMSFileRepository.class.getDeclaredField("EMPLOYEE_CONFIG");
            configField.setAccessible(true);
            Object employeeConfig = configField.get(null);

            java.lang.reflect.Method rootMethod =
                    DMSFileRepository.class.getDeclaredMethod(
                            "findOrCreateRootPath",
                            employeeConfig.getClass());
            rootMethod.setAccessible(true);
            DMSFile root = (DMSFile) rootMethod.invoke(dmsFiles, employeeConfig);

            DMSFile entityHome = new DMSFile();
            entityHome.setFileName("00001");
            entityHome.setIsDirectory(true);
            entityHome.setParent(root);
            entityHome.setRelatedId(1L);
            entityHome.setRelatedModel("com.axelor.apps.hr.db.Employee");
            entityHome = dmsFiles.save(entityHome);

            assertNotNull(entityHome, "Entity home folder should be created");
            assertTrue(entityHome.getIsDirectory(), "Should be a directory");
            assertEquals("00001", entityHome.getFileName(), "Should use padded ID");
            assertEquals(Long.valueOf(1L), entityHome.getRelatedId(), "Should have entity ID");
            assertEquals("com.axelor.apps.hr.db.Employee", entityHome.getRelatedModel(),
                    "Should have Employee as related model");
            assertNotNull(entityHome.getParent(), "Should have parent (root folder)");
            assertEquals("Employee data", entityHome.getParent().getFileName(),
                    "Parent should be root folder");

        } catch (Exception e) {
            throw new RuntimeException("Test failed due to reflection error", e);
        }
    }

    @Test
    @Transactional
    public void testFindOrCreateEntityHome_UsesIdWhenNoName() {
        try {
            java.lang.reflect.Field configField =
                    DMSFileRepository.class.getDeclaredField("EMPLOYEE_CONFIG");
            configField.setAccessible(true);
            Object employeeConfig = configField.get(null);

            java.lang.reflect.Method rootMethod =
                    DMSFileRepository.class.getDeclaredMethod(
                            "findOrCreateRootPath",
                            employeeConfig.getClass());
            rootMethod.setAccessible(true);
            DMSFile root = (DMSFile) rootMethod.invoke(dmsFiles, employeeConfig);

            DMSFile entityHome = new DMSFile();
            entityHome.setFileName("00042");
            entityHome.setIsDirectory(true);
            entityHome.setParent(root);
            entityHome.setRelatedId(42L);
            entityHome.setRelatedModel("com.axelor.apps.hr.db.Employee");
            entityHome = dmsFiles.save(entityHome);

            assertNotNull(entityHome, "Entity home folder should be created");
            assertEquals("00042", entityHome.getFileName(),
                    "Should use padded ID when no name available");
            assertEquals(Long.valueOf(42L), entityHome.getRelatedId(), "Related ID should be 42");

        } catch (Exception e) {
            throw new RuntimeException("Test failed due to reflection error", e);
        }
    }

    // Folder Cleanup Tests

    @Test
    @Transactional
    public void testCleanupEmptyFolderChain_RemovesEmptyFolders() {
        try {
            DMSFile root = new DMSFile();
            root.setFileName("Root");
            root.setIsDirectory(true);
            root = dmsFiles.save(root);

            DMSFile parent = new DMSFile();
            parent.setFileName("Parent");
            parent.setIsDirectory(true);
            parent.setParent(root);
            parent = dmsFiles.save(parent);

            DMSFile child = new DMSFile();
            child.setFileName("Child");
            child.setIsDirectory(true);
            child.setParent(parent);
            child = dmsFiles.save(child);

            Long parentId = parent.getId();
            Long childId = child.getId();

            java.lang.reflect.Method method =
                    DMSFileRepository.class.getDeclaredMethod(
                            "cleanupEmptyFolderChain", DMSFile.class);
            method.setAccessible(true);

            method.invoke(dmsFiles, child);

            assertNull(dmsFiles.find(childId), "Empty child folder should be deleted");

            assertNull(dmsFiles.find(parentId), "Empty parent folder should be deleted");

        } catch (Exception e) {
            throw new RuntimeException("Test failed due to reflection error", e);
        }
    }

    @Test
    @Transactional
    public void testCleanupEmptyFolderChain_KeepsFoldersWithChildren() {
        try {
            DMSFile root = new DMSFile();
            root.setFileName("Root");
            root.setIsDirectory(true);
            root = dmsFiles.save(root);

            DMSFile parent = new DMSFile();
            parent.setFileName("Parent");
            parent.setIsDirectory(true);
            parent.setParent(root);
            parent = dmsFiles.save(parent);

            DMSFile child1 = new DMSFile();
            child1.setFileName("Child1");
            child1.setIsDirectory(true);
            child1.setParent(parent);
            child1 = dmsFiles.save(child1);

            DMSFile child2 = new DMSFile();
            child2.setFileName("Child2");
            child2.setIsDirectory(true);
            child2.setParent(parent);
            child2 = dmsFiles.save(child2);

            Long parentId = parent.getId();
            Long child1Id = child1.getId();

            java.lang.reflect.Method method =
                    DMSFileRepository.class.getDeclaredMethod(
                            "cleanupEmptyFolderChain", DMSFile.class);
            method.setAccessible(true);

            method.invoke(dmsFiles, child1);

            assertNull(dmsFiles.find(child1Id), "Empty child1 should be deleted");

            assertNotNull(dmsFiles.find(parentId),
                    "Parent should not be deleted when it has other children");

        } catch (Exception e) {
            throw new RuntimeException("Test failed due to reflection error", e);
        }
    }

    // Move File Tests

    @Test
    @Transactional
    public void testMoveFileToCorrectHome_MovesFile() {
        DMSFile root = new DMSFile();
        root.setFileName("Employee data");
        root.setIsDirectory(true);
        root.setRelatedModel("com.axelor.apps.hr.db.Employee");
        root = dmsFiles.save(root);

        DMSFile home1 = new DMSFile();
        home1.setFileName("Employee One");
        home1.setIsDirectory(true);
        home1.setParent(root);
        home1.setRelatedId(1L);
        home1.setRelatedModel("com.axelor.apps.hr.db.Employee");
        home1 = dmsFiles.save(home1);

        DMSFile file = new DMSFile();
        file.setFileName("TestFile.txt");
        file.setIsDirectory(false);
        file.setParent(home1);
        file.setRelatedId(1L);
        file.setRelatedModel("com.axelor.apps.hr.db.Employee");
        file = dmsFiles.save(file);

        Long fileId = file.getId();

        DMSFile savedFile = dmsFiles.find(fileId);
        assertNotNull(savedFile, "File should exist");
        assertEquals(home1.getId(), savedFile.getParent().getId(),
                "File should initially be in home1");
    }

    @Test
    @Transactional
    public void testFindHomeByRelated_FindsStandardHome() {
        DMSFile root = new DMSFile();
        root.setFileName("Contacts");
        root.setIsDirectory(true);
        root.setRelatedModel("com.axelor.test.db.Contact");
        root = dmsFiles.save(root);

        DMSFile entityHome = new DMSFile();
        entityHome.setFileName("Test Contact");
        entityHome.setIsDirectory(true);
        entityHome.setParent(root);
        entityHome.setRelatedId(1L);
        entityHome.setRelatedModel("com.axelor.test.db.Contact");
        entityHome = dmsFiles.save(entityHome);

        assertNotNull(entityHome, "Entity home should be created");
        assertEquals("Test Contact", entityHome.getFileName(), "Home name should match");
        assertEquals(Long.valueOf(1L), entityHome.getRelatedId(), "Related ID should match");
        assertEquals("com.axelor.test.db.Contact", entityHome.getRelatedModel(),
                "Related model should match");
        assertNotNull(entityHome.getParent(), "Should have parent");
        assertEquals("Contacts", entityHome.getParent().getFileName(), "Parent name should match");
    }

    // Standard Home Creation Tests

    @Test
    @Transactional
    public void testFindOrCreateStandardHome_CreatesStandardStructure() {
        try {
            com.axelor.test.db.Contact contact = new com.axelor.test.db.Contact();
            contact.setFirstName("John");
            contact.setLastName("Doe");
            contact = com.axelor.db.JPA.persist(contact);

            java.lang.reflect.Method method =
                    DMSFileRepository.class.getDeclaredMethod(
                            "findOrCreateStandardHome", Model.class);
            method.setAccessible(true);

            DMSFile result = (DMSFile) method.invoke(dmsFiles, contact);

            assertNotNull(result, "Standard home folder should be created");
            assertTrue(result.getIsDirectory(), "Should be a directory");
            assertEquals("John Doe", result.getFileName(),
                    "Should use entity name field (firstName + lastName)");
            assertEquals(contact.getId(), result.getRelatedId(),
                    "Should have contact ID");
            assertEquals("com.axelor.test.db.Contact", result.getRelatedModel(),
                    "Should have Contact as related model");

            DMSFile root = result.getParent();
            assertNotNull(root, "Should have parent (root folder)");
            assertEquals("Contacts", root.getFileName(),
                    "Root folder should be pluralized entity name");
            assertTrue(root.getIsDirectory(), "Root should be a directory");
            assertEquals("com.axelor.test.db.Contact", root.getRelatedModel(),
                    "Root should have Contact as related model");
            assertEquals(Long.valueOf(0L), root.getRelatedId(),
                    "Root folder should have relatedId = 0");
            assertNull(root.getParent(), "Root folder should have no parent");

            DMSFile result2 = (DMSFile) method.invoke(dmsFiles, contact);
            assertEquals(result.getId(), result2.getId(),
                    "Should return existing home, not create new one");

            long rootCount = dmsFiles.all()
                    .filter("self.fileName = ?1 AND self.relatedModel = ?2 AND self.relatedId = 0",
                            "Contacts", "com.axelor.test.db.Contact")
                    .count();
            assertEquals(1L, rootCount, "Should only have one root 'Contacts' folder");

        } catch (Exception e) {
            throw new RuntimeException("Test failed due to reflection error", e);
        }
    }

    @Test
    @Transactional
    public void testFindOrCreateStandardHome_UsesIdWhenNoNameField() {
        try {
            com.axelor.test.db.Contact contact = new com.axelor.test.db.Contact();
            contact.setFirstName("Test");
            contact.setLastName("User");
            contact = com.axelor.db.JPA.persist(contact);

            java.lang.reflect.Method method =
                    DMSFileRepository.class.getDeclaredMethod(
                            "findOrCreateStandardHome", Model.class);
            method.setAccessible(true);

            DMSFile result = (DMSFile) method.invoke(dmsFiles, contact);

            assertNotNull(result, "Standard home folder should be created");

            assertEquals("Test User", result.getFileName(),
                    "Should use fullName from Contact");

        } catch (Exception e) {
            throw new RuntimeException("Test failed due to reflection error", e);
        }
    }

    @Test
    @Transactional
    public void testFindOrCreateStandardHome_MultipleEntitiesSeparateFolders() {
        try {
            com.axelor.test.db.Contact contact1 = new com.axelor.test.db.Contact();
            contact1.setFirstName("Alice");
            contact1.setLastName("Smith");
            contact1 = com.axelor.db.JPA.persist(contact1);

            com.axelor.test.db.Contact contact2 = new com.axelor.test.db.Contact();
            contact2.setFirstName("Bob");
            contact2.setLastName("Jones");
            contact2 = com.axelor.db.JPA.persist(contact2);

            java.lang.reflect.Method method =
                    DMSFileRepository.class.getDeclaredMethod(
                            "findOrCreateStandardHome", Model.class);
            method.setAccessible(true);

            DMSFile home1 = (DMSFile) method.invoke(dmsFiles, contact1);
            DMSFile home2 = (DMSFile) method.invoke(dmsFiles, contact2);

            assertNotNull(home1, "Contact 1 should have home");
            assertNotNull(home2, "Contact 2 should have home");

            assertTrue(!home1.getId().equals(home2.getId()),
                    "Each contact should have separate home folder");

            assertEquals("Alice Smith", home1.getFileName(), "Contact 1 folder name");
            assertEquals("Bob Jones", home2.getFileName(), "Contact 2 folder name");

            assertEquals(home1.getParent().getId(), home2.getParent().getId(),
                    "Both should share the same root 'Contacts' folder");
            assertEquals("Contacts", home1.getParent().getFileName(),
                    "Root folder should be 'Contacts'");

        } catch (Exception e) {
            throw new RuntimeException("Test failed due to reflection error", e);
        }
    }
}