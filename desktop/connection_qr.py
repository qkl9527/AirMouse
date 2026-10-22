"""本地生成网页与终端的连接二维码，不需要手机或电脑访问二维码 CDN。"""

import base64
import io
import os
import sys
from functools import lru_cache

import qrcode
from qrcode.image.svg import SvgPathImage


def make_connection_qr(url):
    # 四模块白边是扫码所需的静区；二维码版本由地址长度自动决定。
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4)
    qr.add_data(url)
    qr.make(fit=True)
    return qr


@lru_cache(maxsize=128)
def qr_image_data(url):
    """生成内嵌 SVG 图片，重复打开弹窗不必重新编码同一个地址。"""
    image = make_connection_qr(url).make_image(image_factory=SvgPathImage)
    encoded = base64.b64encode(image.to_string()).decode('ascii')
    return f'data:image/svg+xml;base64,{encoded}'


def print_terminal_qr(url, stream=None):
    """深浅色终端统一输出黑白二维码；打包后没有 stdout 时直接跳过。"""
    stream = stream if stream is not None else sys.stdout
    if stream is None:
        return
    output = io.StringIO()
    make_connection_qr(url).print_ascii(out=output, invert=True)
    text = output.getvalue()
    try:
        # 支持颜色的交互终端明确设置白色前景、黑色背景，防止主题反转影响扫码。
        if stream.isatty() and os.environ.get('TERM') != 'dumb':
            text = '\x1b[40;97m' + text.replace('\n', '\x1b[0m\n\x1b[40;97m') + '\x1b[0m'
        text.encode(getattr(stream, 'encoding', None) or 'utf-8')
        stream.write(text)
        stream.flush()
    except (UnicodeError, OSError):
        # 控制台不支持块字符时仍保留启动信息中的 URL，不能阻止控制服务启动。
        stream.write(f'QR unavailable in this terminal. Open: {url}\n')

