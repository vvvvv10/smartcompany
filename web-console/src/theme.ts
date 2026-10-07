import type { ThemeConfig } from 'antd'

/** 统一色板与渐变，避免各页面自己写颜色导致风格漂移 */
export const COLOR = {
    primary: '#4f46e5',
    primarySoft: '#eef2ff',
    gradient: 'linear-gradient(135deg, #4f46e5 0%, #7c3aed 55%, #a855f7 100%)',
    gradientSoft: 'linear-gradient(135deg, rgba(79,70,229,0.08) 0%, rgba(168,85,247,0.08) 100%)',
    text: '#101828',
    textMuted: '#667085',
    border: '#eaecf0',
    bg: '#f6f7fb',
    chart: ['#6366f1', '#10b981', '#f59e0b', '#ec4899', '#8b5cf6']
}

export const themeConfig: ThemeConfig = {
    token: {
        colorPrimary: COLOR.primary,
        colorInfo: COLOR.primary,
        colorTextHeading: COLOR.text,
        colorTextSecondary: COLOR.textMuted,
        borderRadius: 10,
        colorBgLayout: COLOR.bg,
        colorBorderSecondary: COLOR.border,
        fontSize: 14,
        controlHeight: 36,
        fontFamily:
            "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    },
    components: {
        Layout: {
            headerBg: '#ffffff',
            siderBg: '#ffffff',
            bodyBg: COLOR.bg,
            headerHeight: 60,
            headerPadding: '0 24px'
        },
        Menu: {
            itemBorderRadius: 8,
            itemSelectedBg: COLOR.primarySoft,
            itemSelectedColor: COLOR.primary,
            itemHeight: 40,
            itemMarginInline: 10,
            itemMarginBlock: 4
        },
        Card: {
            borderRadiusLG: 14,
            paddingLG: 20,
            colorBorderSecondary: COLOR.border
        },
        Table: {
            headerBg: '#fafbfc',
            headerColor: COLOR.textMuted,
            borderColor: '#f0f1f4',
            rowHoverBg: '#fafbfd'
        },
        Button: {
            borderRadius: 10,
            controlHeight: 36,
            primaryShadow: '0 1px 2px rgba(79,70,229,0.24)'
        },
        Input: {
            borderRadius: 10,
            controlHeight: 36
        },
        Tag: {
            borderRadiusSM: 6
        }
    }
}
