/**
 * Vitest/jsdom 的唯一测试初始化入口。
 *
 * 仅补齐 Element Plus 组件在 jsdom 中需要的浏览器观察器，不提供接口或业务行为 Mock。
 */
class TestResizeObserver {
  constructor(_callback: ResizeObserverCallback) {}

  observe(_target: Element, _options?: ResizeObserverOptions): void {}

  unobserve(_target: Element): void {}

  disconnect(): void {}
}

if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = TestResizeObserver
}
