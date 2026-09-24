/**
 * 服务端预估调度器（任务 2.17）：约 300ms 防抖、取消旧请求、以递增序号拒绝过期响应。
 * 与 React 解耦，便于用假定时器直接验证防抖与竞态；组件只负责把计算输入交给它并渲染状态。
 */

export type PreviewStatus = 'idle' | 'loading' | 'ready' | 'failed';

export interface PreviewSnapshot<TResult> {
  status: PreviewStatus;
  result: TResult | null;
}

export interface PreviewScheduler<TInput, TResult> {
  /** 计算输入变化：null 表示字段未齐（待填写），不发起请求。 */
  request(input: TInput | null): void;
  /** 保存成功：以服务端保存结果覆盖预估，并让在途试算失效。 */
  showSaved(result: TResult): void;
  /** 失败后重试上一次输入。 */
  retry(): void;
  /** 取消定时器与在途请求（离开页面或保存前）。 */
  cancel(): void;
}

export interface PreviewSchedulerOptions<TInput, TResult> {
  load: (input: TInput, signal: AbortSignal) => Promise<TResult>;
  onChange: (snapshot: PreviewSnapshot<TResult>) => void;
  debounceMs?: number;
}

const DEFAULT_DEBOUNCE_MS = 300;

export function createPreviewScheduler<TInput, TResult>(
  options: PreviewSchedulerOptions<TInput, TResult>,
): PreviewScheduler<TInput, TResult> {
  const debounceMs = options.debounceMs ?? DEFAULT_DEBOUNCE_MS;
  let sequence = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let controller: AbortController | null = null;
  let currentKey: string | null = null;
  let lastInput: TInput | null = null;

  function clearTimer() {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  }

  function abortInFlight() {
    if (controller !== null) {
      controller.abort();
      controller = null;
    }
  }

  function invalidate() {
    sequence += 1;
    clearTimer();
    abortInFlight();
    currentKey = null;
  }

  function request(input: TInput | null) {
    const key = input === null ? null : JSON.stringify(input);
    if (input !== null && key === currentKey) {
      // 名称/备注等无关字段变化不触发试算
      return;
    }
    lastInput = input;
    invalidate();
    const ticket = sequence;
    if (input === null) {
      options.onChange({ status: 'idle', result: null });
      return;
    }
    // 输入已变：旧结果立即失效，先显示计算中
    options.onChange({ status: 'loading', result: null });
    currentKey = key;
    timer = setTimeout(() => {
      timer = null;
      const pending = new AbortController();
      controller = pending;
      options.load(input, pending.signal).then(
        result => {
          if (ticket !== sequence) {
            return;
          }
          controller = null;
          options.onChange({ status: 'ready', result });
        },
        () => {
          if (ticket !== sequence || pending.signal.aborted) {
            return;
          }
          controller = null;
          options.onChange({ status: 'failed', result: null });
        },
      );
    }, debounceMs);
  }

  return {
    request,
    showSaved(result) {
      invalidate();
      options.onChange({ status: 'ready', result });
    },
    retry() {
      const input = lastInput;
      if (input === null) {
        return;
      }
      currentKey = null;
      request(input);
    },
    cancel() {
      invalidate();
      options.onChange({ status: 'idle', result: null });
    },
  };
}
