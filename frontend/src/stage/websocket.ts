import type { Transport } from './controller.ts'
export class WebSocketTransport implements Transport {
  private socket: WebSocket | null = null
  connect(handlers: Parameters<Transport['connect']>[0]) {
    const url = new URL('/ws/stage', window.location.href)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    const socket = new WebSocket(url); this.socket = socket
    socket.onopen = handlers.open
    socket.onmessage = event => handlers.message(typeof event.data === 'string' ? event.data : '')
    socket.onclose = handlers.close
    socket.onerror = handlers.close
  }
  send(raw: string) {
    if (this.socket?.readyState !== WebSocket.OPEN) throw new Error('DISCONNECTED')
    this.socket.send(raw)
  }
  close() {
    if (!this.socket) return
    this.socket.onopen = this.socket.onmessage = this.socket.onclose = this.socket.onerror = null
    this.socket.close(); this.socket = null
  }
}
