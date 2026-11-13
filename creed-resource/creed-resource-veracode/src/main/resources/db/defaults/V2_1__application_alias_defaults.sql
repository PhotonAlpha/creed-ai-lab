-- What application.yml's veracode.applications held when the aliases moved into the database (2026-11-13). Its own
-- location (spring.flyway.locations), so the tests - schema only - start without them.
INSERT INTO application_alias (app_key, app_name, alias, repository_url, updated_at) VALUES
    ('gbt-t1-creed-ccs', 'GBT-T1-Creed-CCS', 'Creed-CCS', 'https://github.com/PhotonAlpha/creed-ai-lab', CURRENT_TIMESTAMP),
    ('creed-platform-2026-10-06-creed', 'creed-platform-2026-10-06-Creed', 'Creed-Oct',
     'https://github.com/PhotonAlpha/creed-ai-lab', CURRENT_TIMESTAMP),
    ('creed-gateway-2026-10-08-gateway', 'creed-gateway-2026-10-08-Gateway', 'Creed-Oct-2',
     'https://github.com/PhotonAlpha/creed-ai-lab', CURRENT_TIMESTAMP);
