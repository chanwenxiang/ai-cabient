-- V131: remove unused gamification check-in / game-task stacks (no API/UI)

DROP TABLE IF EXISTS user_game_task;
DROP TABLE IF EXISTS game_task;
DROP TABLE IF EXISTS user_checkin;
