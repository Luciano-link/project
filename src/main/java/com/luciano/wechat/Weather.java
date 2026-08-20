package com.luciano.wechat;

/**
 * 天气数据模型类:承载和风天气接口返回的结构化字段,替代字符串拼接。
 */
public final class Weather {

    private Weather() {
    }

    /**
     * 实时天气(实况)。
     *
     * @param city      城市名(查询时使用的名字)
     * @param text      天气现象(如「多云」)
     * @param temp      气温(℃)
     * @param feelsLike 体感温度(℃)
     * @param humidity  相对湿度(%)
     * @param windDir   风向(如「东南风」)
     * @param windScale 风力等级
     * @param windSpeed 风速(km/h)
     * @param precip    过去 1 小时降水量(mm)
     * @param pressure  大气压强(hPa)
     * @param vis       能见度(km)
     */
    public record Current(String city, String text, String temp, String feelsLike,
                          String humidity, String windDir, String windScale,
                          String windSpeed, String precip, String pressure, String vis) {
    }

    /**
     * 单日预报(白天/夜间天气、气温范围、风向风力、日出日落、紫外线等)。
     *
     * @param date        日期(YYYY-MM-DD)
     * @param textDay     白天天气现象
     * @param textNight   夜间天气现象
     * @param tempMin     最低气温(℃)
     * @param tempMax     最高气温(℃)
     * @param windDirDay  白天风向
     * @param windScaleDay 白天风力等级
     * @param sunrise     日出时间(HH:mm)
     * @param sunset      日落时间(HH:mm)
     * @param uvIndex     紫外线指数
     * @param humidity    相对湿度(%)
     * @param precip      降水量(mm)
     */
    public record Daily(String date, String textDay, String textNight,
                        String tempMin, String tempMax, String windDirDay, String windScaleDay,
                        String sunrise, String sunset, String uvIndex, String humidity, String precip) {
    }

    /**
     * 空气质量实况(AQI)。
     *
     * @param aqi      空气质量指数
     * @param category 空气质量类别(优/良/轻度污染…)
     * @param primary  首要污染物(无则空)
     * @param pm2p5    PM2.5 浓度(μg/m³)
     * @param pm10     PM10 浓度(μg/m³)
     */
    public record Air(String aqi, String category, String primary, String pm2p5, String pm10) {
    }

    /**
     * 生活指数(穿衣/运动/紫外线/洗车等)。
     *
     * @param name     指数名称(如「穿衣指数」)
     * @param category 等级类别(如「舒适」「较适宜」)
     * @param text     详细建议文案
     */
    public record IndexItem(String name, String category, String text) {
    }
}
