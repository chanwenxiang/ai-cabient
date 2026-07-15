-- 从旧库 ego-automat (MySQL) 导出用户
-- 用法: mysql -u root -p ego_automat < export_users.sql > users.csv
-- 或:   mysql ... -e "source export_users.sql" --batch --raw

SELECT
    u.user_id,
    u.phone_number,
    COALESCE(u.name, '') AS name,
    IF(u.verify = 1, 1, 0) AS verified,
    COALESCE(a.balance, 0) AS balance_cents
FROM m8_user_info u
LEFT JOIN m8_user_account a ON a.user_id = u.user_id AND a.archived = 0
WHERE u.archived = 0
  AND u.phone_number IS NOT NULL
  AND u.phone_number != ''
ORDER BY u.user_id;
