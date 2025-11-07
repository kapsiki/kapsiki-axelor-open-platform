package com.axelor.dms.web;

import com.axelor.db.JpaRepository;
import com.axelor.db.Model;
import com.axelor.dms.db.DMSFile;
import com.axelor.meta.db.MetaModel;
import com.axelor.meta.db.repo.MetaModelRepository;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Inject;
import java.lang.reflect.Method;

public class DMSFileController {

    @Inject
    private MetaModelRepository metaModelRepo;

    /**
     * Open the record related to a DMS file
     */
    public void viewRelatedRecord(ActionRequest request, ActionResponse response) {
        DMSFile file = request.getContext().asType(DMSFile.class);

        if (file.getRelatedModel() == null || file.getRelatedId() == null) {
            response.setInfo("This file has no related record");
            return;
        }

        String model = file.getRelatedModel();
        Long id = file.getRelatedId();

        MetaModel metaModel = metaModelRepo.findByName(model);
        String title = metaModel != null ? metaModel.getFullName() : "View Record";

        ActionView.ActionViewBuilder builder = ActionView
                .define(title)
                .model(model)
                .param("forceEdit", "true")
                .context("_showRecord", id);

        if ("com.axelor.apps.project.db.Project".equals(model)) {
            try {
                @SuppressWarnings("unchecked")
                JpaRepository<? extends Model> repo = JpaRepository.of((Class<? extends Model>) Class.forName(model));
                Model record = repo.find(id);

                Method getter = record.getClass().getMethod("getIsBusinessProject");
                Boolean isBusinessProject = (Boolean) getter.invoke(record);

                if (Boolean.TRUE.equals(isBusinessProject)) {
                    System.out.println("[viewRelatedRecord] using buisiness project form");
                    builder.add("form", "business-project-form");
                    builder.add("grid", "business-project-grid");
                } else {
                    System.out.println("[viewRelatedRecord] using project form");

                    builder.add("form", "project-form");
                    builder.add("grid", "project-grid");
                }
            } catch (Exception e) {
                System.out.println("[viewRelatedRecord] Exception! using project form");
                builder.add("form");
                builder.add("grid");
            }
        } else {
            System.out.println("[viewRelatedRecord] not a project using it's form");
            builder.add("form");
            builder.add("grid");
        }

        response.setView(builder.map());
    }
}