// Explicitly manual, private examples. Never calls an external provider.
const base = process.env.SMARTTRIP_API_URL || 'http://127.0.0.1:8080/api';
const password = process.env.SMARTTRIP_DEMO_PASSWORD;
if (!password) throw new Error('Set SMARTTRIP_DEMO_PASSWORD for the existing demo_traveler account');
async function call(path, method = 'GET', body, token) {
  const response = await fetch(base + path, { method, headers: {
    ...(body ? { 'Content-Type': 'application/json' } : {}),
    ...(token ? { Authorization: 'Bearer ' + token } : {}),
  }, body: body ? JSON.stringify(body) : undefined });
  const value = await response.json();
  if (!response.ok) throw new Error(path + ': ' + response.status);
  return value.data;
}
const { token } = await call('/auth/login', 'POST', { username: 'demo_traveler', password });
const existing = await call('/workbench', 'GET', undefined, token);
const item = (itemName, itemType, startTime, costTimeHours) => ({ itemName, itemType, startTime, costTimeHours, costEstimate: 0 });
const examples = [
  { title: '质量示例① · 适中短日程（手动）', days: [{ dayTheme: '留出午餐和计划间隔', items: [
    item('上午城市展馆', 'SCENIC', '09:00', 2), item('午餐安排', 'FOOD', '12:00', 1), item('下午公园漫步', 'SCENIC', '14:00', 2),
  ] }] },
  { title: '质量示例② · 无冲突但午餐紧张（手动）', days: [{ dayTheme: '连续安排，需手动预留餐休', items: [
    item('上午展馆', 'SCENIC', '09:00', 2), item('中午街区', 'SCENIC', '11:00', 2), item('下午展馆', 'SCENIC', '13:00', 2),
  ] }] },
  { title: '质量示例③ · 缺时间与跨日（手动）', days: [{ dayTheme: '信息不足，暂不评总分', items: [
    item('待安排的展馆', 'SCENIC', undefined, 2), item('跨午夜安排', 'SCENIC', '23:00', 2),
  ] }] },
];
for (const example of examples) {
  let trip = existing.find(t => t.title === example.title);
  const created = !trip;
  if (!trip) trip = await call('/workbench', 'POST', { ...example, destination: '成都', startDate: '2026-10-10',
    summary: '第五轮手动验收示例，不是AI生成；无供应商地点、路线、天气或营业信息。仅用于解释规则评分。',
  }, token);
  trip = await call('/workbench/' + trip.id, 'GET', undefined, token);
  const report = await call('/workbench/' + trip.id + '/analyses', 'POST', {
    baseVersion: trip.version, dayIds: [trip.days[0].id], mode: 'walking',
  }, token);
  console.log(JSON.stringify({ id: trip.id, title: trip.title, created, status: report.quality.status,
    totalScore: report.quality.totalScore, rules: report.quality.findings.map(f => f.ruleId) }));
}
