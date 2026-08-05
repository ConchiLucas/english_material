import React from 'react';
import ReactDOM from 'react-dom/client';
import { App as AntdApp, ConfigProvider, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import './styles.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#5e6ad2',
          colorPrimaryHover: '#7c86e8',
          colorPrimaryActive: '#4e59b8',
          colorInfo: '#5e9be8',
          colorSuccess: '#32b879',
          colorWarning: '#d99a35',
          colorError: '#e85d68',
          colorBgBase: '#0b0d10',
          colorBgContainer: '#13171d',
          colorBgElevated: '#191e26',
          colorBorder: '#2b323d',
          colorBorderSecondary: '#242b35',
          colorText: '#f4f6f8',
          colorTextSecondary: '#b9c0ca',
          colorTextTertiary: '#8b95a3',
          colorTextDisabled: '#656e7b',
          borderRadius: 8,
          borderRadiusLG: 12,
          controlHeight: 40,
          controlHeightLG: 44,
          fontSize: 14,
          fontFamily:
            'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif',
        },
        components: {
          Button: {
            primaryShadow: 'none',
            defaultBg: '#191e26',
            defaultBorderColor: '#424b59',
            defaultColor: '#f4f6f8',
          },
          Card: {
            borderRadiusLG: 12,
            colorBorderSecondary: '#2b323d',
          },
          Input: {
            colorBgContainer: '#0b0d10',
            activeBorderColor: '#9aa2ff',
            hoverBorderColor: '#6975dc',
          },
          InputNumber: {
            colorBgContainer: '#0b0d10',
            activeBorderColor: '#9aa2ff',
            hoverBorderColor: '#6975dc',
          },
          Select: {
            colorBgContainer: '#0b0d10',
            optionSelectedBg: '#242946',
          },
          Menu: {
            darkItemBg: '#0e1116',
            darkItemColor: '#b9c0ca',
            darkItemHoverBg: '#191e26',
            darkItemSelectedBg: '#242946',
            darkItemSelectedColor: '#ffffff',
          },
          Modal: {
            contentBg: '#13171d',
            headerBg: '#13171d',
          },
          Layout: {
            bodyBg: '#0b0d10',
            siderBg: '#0e1116',
            headerBg: '#0b0d10',
          },
        },
      }}
    >
      <AntdApp>
        <App />
      </AntdApp>
    </ConfigProvider>
  </React.StrictMode>,
);
