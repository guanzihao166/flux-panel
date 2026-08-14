import { Dropdown, DropdownItem, DropdownMenu, DropdownTrigger } from '@heroui/dropdown';
import { Button } from '@heroui/button';
import { useAppTheme, ThemePreference } from '@/components/theme-provider';

const labels: Record<ThemePreference, string> = {
  auto: '自动主题',
  light: '浅色模式',
  dark: '深色模式',
};

const icons: Record<ThemePreference, string> = {
  auto: '◐',
  light: '☀',
  dark: '☾',
};

export function ThemeSwitcher({ compact = false }: { compact?: boolean }) {
  const { preference, resolvedTheme, setPreference } = useAppTheme();

  return (
    <Dropdown placement="bottom-end">
      <DropdownTrigger>
        <Button
          isIconOnly={compact}
          size="sm"
          variant="light"
          aria-label={`主题：${labels[preference]}`}
          title={`主题：${labels[preference]}（当前${resolvedTheme === 'dark' ? '深色' : '浅色'}）`}
          className={compact ? '' : 'min-w-24'}
        >
          <span aria-hidden className="text-lg leading-none">{icons[preference]}</span>
          {!compact && <span className="ml-1 text-xs">{labels[preference]}</span>}
        </Button>
      </DropdownTrigger>
      <DropdownMenu
        aria-label="主题设置"
        selectionMode="single"
        selectedKeys={[preference]}
        onAction={(key) => setPreference(key as ThemePreference)}
      >
        <DropdownItem key="auto" description="06:00–18:00 浅色，其余时间深色">自动主题</DropdownItem>
        <DropdownItem key="light">浅色模式</DropdownItem>
        <DropdownItem key="dark">深色模式</DropdownItem>
      </DropdownMenu>
    </Dropdown>
  );
}
