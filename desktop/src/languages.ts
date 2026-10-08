export const languages = [
  ["auto", "自动检测"],
  ["zh-CN", "简体中文"],
  ["zh-TW", "繁體中文"],
  ["en", "英语"],
  ["ja", "日语"],
  ["ko", "韩语"],
  ["fr", "法语"],
  ["de", "德语"],
  ["es", "西班牙语"],
  ["pt", "葡萄牙语"],
  ["ru", "俄语"],
  ["it", "意大利语"],
  ["ar", "阿拉伯语"],
] as const;
export const languageName = (code: string) =>
  languages.find(([value]) => value === code)?.[1] ?? code;
export function formatDate(value: string) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "时间未知";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}
