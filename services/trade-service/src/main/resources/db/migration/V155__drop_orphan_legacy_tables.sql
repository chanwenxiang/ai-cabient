-- V155: drop orphan tables whose Java feature stacks were never wired to services/APIs
-- (ad management, social group-buy/red-packet, edge computing, line routes)

DROP TABLE IF EXISTS ad_impression;
DROP TABLE IF EXISTS ad_campaign;
DROP TABLE IF EXISTS ad_slot;

DROP TABLE IF EXISTS group_buy_participant;
DROP TABLE IF EXISTS group_buy;

DROP TABLE IF EXISTS red_packet_claim;
DROP TABLE IF EXISTS red_packet;

DROP TABLE IF EXISTS edge_inference_log;
DROP TABLE IF EXISTS edge_model_version;
DROP TABLE IF EXISTS edge_device;

DROP TABLE IF EXISTS line_route;
