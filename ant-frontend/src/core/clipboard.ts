import { message } from 'antd';

export async function copyText(value: string, label = '内容') {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
    } else {
      const textarea = document.createElement('textarea');
      textarea.value = value;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      try {
        textarea.select();
        if (!document.execCommand('copy')) throw new Error('浏览器拒绝复制');
      } finally { textarea.remove(); }
    }
    message.success(`${label}已复制`);
  } catch (cause) {
    message.error(`复制失败：${cause instanceof Error ? cause.message : String(cause)}`);
  }
}
