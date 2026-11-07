

INSERT INTO system_dict VALUES (5028,0, 'idb-modules', 0, 0);


INSERT INTO system_dict VALUES (5029,5028, 'idbcommonutils', 1, 0);


INSERT INTO system_dict VALUES (5030,5028, 'idbconfigs', 0, 0);


INSERT INTO system_dict VALUES (5031,5028, 'idbproxy', 2, 0);


INSERT INTO system_dict VALUES (5032,5028, 'idbnotificationservice', 3, 0);


INSERT INTO system_dict VALUES (5033,5028, 'idb-batch-service', 4, 0);


INSERT INTO system_dict VALUES (5034,5028, 'idb-global-ccs', 5, 0);


INSERT INTO system_dict VALUES (5035,5028, 'idb-fdr-ccs-gw', 5, 0);


INSERT INTO system_dict VALUES (5036,5028, 'idb-fdr-integration-service', 5, 0);


INSERT INTO system_dict VALUES (5037,5028, 'idb-fdr-fraudops-service', 5, 0);


INSERT INTO system_dict VALUES (5038,5028, 'idb-fdr-fraudops-admin-service', 5, 0);


INSERT INTO system_dict VALUES (5039,5028, 'idb-ccs-gw-security', 0, 0);




ALTER TABLE system_dict ALTER COLUMN "ID" RESTART WITH (SELECT MAX(ID) + 1 FROM  system_dict);