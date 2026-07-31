-- V130: remove unused franchise + achievement stacks (no API/UI ever shipped)

DROP TABLE IF EXISTS franchise_settlement;
DROP TABLE IF EXISTS franchise_device;
DROP TABLE IF EXISTS franchise;

DROP TABLE IF EXISTS user_achievement;
DROP TABLE IF EXISTS achievement;
