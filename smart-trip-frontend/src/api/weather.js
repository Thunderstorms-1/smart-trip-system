import request from '@/utils/request'

// 获取指定目的地和日期的气象与出行预警预测
export function getWeatherForecastApi(destination, startDate) {
  return request({
    url: '/api/weather/forecast',
    method: 'GET',
    params: { destination, startDate }
  })
}