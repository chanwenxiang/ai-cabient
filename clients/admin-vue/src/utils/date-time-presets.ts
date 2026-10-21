/**
 * datetimerange 面板起止的默认时分秒。
 *
 * 不配置时 Element Plus 默认把起止草稿都填成「当天 00:00:00」，两侧照默认确认
 * 会得到零宽区间（from=to=00:00:00），订单/会话列表查不出数据。
 * 止侧固定 23:59:59，使同日区间覆盖全天。
 */
export const DEFAULT_DATETIME_RANGE_TIME: [Date, Date] = [
  new Date(2000, 0, 1, 0, 0, 0),
  new Date(2000, 0, 1, 23, 59, 59)
];
