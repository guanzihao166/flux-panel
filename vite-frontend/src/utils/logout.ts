/**
 * 安全退出登录函数
 * 清除登录相关数据，但保留用户偏好设置（如主题）
 */
export const safeLogout = () => {
  const themePreference = localStorage.getItem('theme-preference');
  localStorage.clear();
  sessionStorage.clear();
  if (themePreference) localStorage.setItem('theme-preference', themePreference);
};
