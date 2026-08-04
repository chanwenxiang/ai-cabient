-- Allocate operator and merchant-team user IDs atomically across concurrent requests.
CREATE SEQUENCE IF NOT EXISTS operator_user_id_seq
    START WITH 100000001
    INCREMENT BY 1;

SELECT setval(
    'operator_user_id_seq',
    GREATEST(
        COALESCE((SELECT MAX(user_id) FROM user_info), 100000000),
        100000000
    ),
    TRUE
);
