package com.aicabinet.trade.service;



import java.util.List;

import org.springframework.http.HttpStatus;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.stereotype.Service;

import org.springframework.web.server.ResponseStatusException;



/**

 * 同一台柜机原地更换 device_id（主键），列表始终只有一条 device_info。

 * 依赖 V245：指向 device_info / device_slot 的外键须 ON UPDATE CASCADE。

 */

@Service

public class DeviceIdRenameService {



    private static final List<String> BLOCKING_TABLES = List.of(

            "replenishment_task",

            "repair_ticket",

            "merchant_replenishment_request",

            "inventory_movement",

            "order_revenue_split",

            "warehouse_in_transit",

            "warehouse_outbound_line",

            "line_device",

            "promotion_device",

            "ad_campaign_device",

            "pull_off_task",

            "site_contract",

            "user_coupon",

            "inventory_write_off");



    private final JdbcTemplate jdbcTemplate;



    public DeviceIdRenameService(JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;

    }



    public void assertRenumberAllowed(String deviceId) {

        if (countByDeviceId("cabinet_order", deviceId) > 0) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已有订单记录，不可重新生成编号");

        }

        if (countByDeviceId("shopping_session", deviceId) > 0) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已有开门记录，不可重新生成编号");

        }

        for (String table : BLOCKING_TABLES) {

            if (countByDeviceId(table, deviceId) > 0) {

                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "存在关联业务数据，不可重新生成编号");

            }

        }

    }



    public void renameInPlace(String oldDeviceId, String newDeviceId) {

        int updated = jdbcTemplate.update(

                "UPDATE device_info SET device_id = ? WHERE device_id = ?",

                newDeviceId, oldDeviceId);

        if (updated != 1) {

            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备编号更新失败");

        }

    }



    private long countByDeviceId(String table, String deviceId) {

        Long count = jdbcTemplate.queryForObject(

                "SELECT COUNT(*) FROM " + table + " WHERE device_id = ?",

                Long.class,

                deviceId);

        return count == null ? 0 : count;

    }

}


