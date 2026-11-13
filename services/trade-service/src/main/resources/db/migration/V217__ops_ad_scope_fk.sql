-- Ops / ad / scope / audit links to device_info, user_info, ad_campaign.
-- Junction & scope rows CASCADE; telemetry/audit RESTRICT or SET NULL.

UPDATE ad_play_event e
SET asset_id = NULL
WHERE e.asset_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM media_asset a WHERE a.asset_id = e.asset_id);

UPDATE risk_event e
SET device_id = NULL
WHERE e.device_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = e.device_id);

UPDATE risk_event e
SET user_id = NULL
WHERE e.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = e.user_id);

UPDATE user_coupon c
SET device_id = NULL
WHERE c.device_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = c.device_id);

UPDATE ops_exception e
SET user_id = NULL
WHERE e.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = e.user_id);

DELETE FROM ad_campaign_device d
WHERE NOT EXISTS (SELECT 1 FROM device_info i WHERE i.device_id = d.device_id);

DELETE FROM ad_play_event e
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = e.device_id)
   OR NOT EXISTS (SELECT 1 FROM ad_campaign c WHERE c.campaign_id = e.campaign_id);

DELETE FROM ops_user_device_scope s
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = s.device_id)
   OR NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = s.user_id);

DELETE FROM ops_user_device_scope_pref p
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = p.user_id);

DELETE FROM ops_user_route_scope s
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = s.user_id);

DELETE FROM ops_2fa_recovery_code c
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = c.user_id);

DELETE FROM ops_user_role r
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.user_id);

DELETE FROM ops_device_org o
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = o.device_id);

DELETE FROM device_env_reading r
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = r.device_id);

DELETE FROM device_temp_plan p
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = p.device_id);

DELETE FROM device_lifecycle_event e
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = e.device_id);

DELETE FROM line_commission_daily c
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = c.device_id);

DELETE FROM ota_device_report r
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = r.device_id);

DELETE FROM site_contract c
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = c.device_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ad_campaign_device_device_id_fkey'
          AND conrelid = 'ad_campaign_device'::regclass
    ) THEN
        ALTER TABLE ad_campaign_device
            ADD CONSTRAINT ad_campaign_device_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ad_play_event_campaign_id_fkey'
          AND conrelid = 'ad_play_event'::regclass
    ) THEN
        ALTER TABLE ad_play_event
            ADD CONSTRAINT ad_play_event_campaign_id_fkey
            FOREIGN KEY (campaign_id) REFERENCES ad_campaign (campaign_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ad_play_event_device_id_fkey'
          AND conrelid = 'ad_play_event'::regclass
    ) THEN
        ALTER TABLE ad_play_event
            ADD CONSTRAINT ad_play_event_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ad_play_event_asset_id_fkey'
          AND conrelid = 'ad_play_event'::regclass
    ) THEN
        ALTER TABLE ad_play_event
            ADD CONSTRAINT ad_play_event_asset_id_fkey
            FOREIGN KEY (asset_id) REFERENCES media_asset (asset_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_user_device_scope_device_id_fkey'
          AND conrelid = 'ops_user_device_scope'::regclass
    ) THEN
        ALTER TABLE ops_user_device_scope
            ADD CONSTRAINT ops_user_device_scope_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_user_device_scope_user_id_fkey'
          AND conrelid = 'ops_user_device_scope'::regclass
    ) THEN
        ALTER TABLE ops_user_device_scope
            ADD CONSTRAINT ops_user_device_scope_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_user_device_scope_pref_user_id_fkey'
          AND conrelid = 'ops_user_device_scope_pref'::regclass
    ) THEN
        ALTER TABLE ops_user_device_scope_pref
            ADD CONSTRAINT ops_user_device_scope_pref_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_user_route_scope_user_id_fkey'
          AND conrelid = 'ops_user_route_scope'::regclass
    ) THEN
        ALTER TABLE ops_user_route_scope
            ADD CONSTRAINT ops_user_route_scope_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_2fa_recovery_code_user_id_fkey'
          AND conrelid = 'ops_2fa_recovery_code'::regclass
    ) THEN
        ALTER TABLE ops_2fa_recovery_code
            ADD CONSTRAINT ops_2fa_recovery_code_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_user_role_user_id_fkey'
          AND conrelid = 'ops_user_role'::regclass
    ) THEN
        ALTER TABLE ops_user_role
            ADD CONSTRAINT ops_user_role_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_device_org_device_id_fkey'
          AND conrelid = 'ops_device_org'::regclass
    ) THEN
        ALTER TABLE ops_device_org
            ADD CONSTRAINT ops_device_org_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'device_env_reading_device_id_fkey'
          AND conrelid = 'device_env_reading'::regclass
    ) THEN
        ALTER TABLE device_env_reading
            ADD CONSTRAINT device_env_reading_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'device_temp_plan_device_id_fkey'
          AND conrelid = 'device_temp_plan'::regclass
    ) THEN
        ALTER TABLE device_temp_plan
            ADD CONSTRAINT device_temp_plan_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'device_lifecycle_event_device_id_fkey'
          AND conrelid = 'device_lifecycle_event'::regclass
    ) THEN
        ALTER TABLE device_lifecycle_event
            ADD CONSTRAINT device_lifecycle_event_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'line_commission_daily_device_id_fkey'
          AND conrelid = 'line_commission_daily'::regclass
    ) THEN
        ALTER TABLE line_commission_daily
            ADD CONSTRAINT line_commission_daily_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ota_device_report_device_id_fkey'
          AND conrelid = 'ota_device_report'::regclass
    ) THEN
        ALTER TABLE ota_device_report
            ADD CONSTRAINT ota_device_report_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'risk_event_device_id_fkey'
          AND conrelid = 'risk_event'::regclass
    ) THEN
        ALTER TABLE risk_event
            ADD CONSTRAINT risk_event_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'risk_event_user_id_fkey'
          AND conrelid = 'risk_event'::regclass
    ) THEN
        ALTER TABLE risk_event
            ADD CONSTRAINT risk_event_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'site_contract_device_id_fkey'
          AND conrelid = 'site_contract'::regclass
    ) THEN
        ALTER TABLE site_contract
            ADD CONSTRAINT site_contract_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_coupon_device_id_fkey'
          AND conrelid = 'user_coupon'::regclass
    ) THEN
        ALTER TABLE user_coupon
            ADD CONSTRAINT user_coupon_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_exception_user_id_fkey'
          AND conrelid = 'ops_exception'::regclass
    ) THEN
        ALTER TABLE ops_exception
            ADD CONSTRAINT ops_exception_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;
END $$;
