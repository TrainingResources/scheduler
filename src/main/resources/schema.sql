CREATE TABLE IF NOT EXISTS user_notification_schedule (
    user_id BIGINT NOT NULL,
    notification_key VARCHAR(100) NOT NULL,
    schedule_config_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, notification_key)
);
