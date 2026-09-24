import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPreviewScheduler, type PreviewSnapshot } from './previewScheduler';

interface Input {
  value: string;
}

type Result = { echo: string };

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('服务端预估调度器（任务 2.17）', () => {
  let snapshots: PreviewSnapshot<Result>[];

  beforeEach(() => {
    vi.useFakeTimers();
    snapshots = [];
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function schedulerWith(load: (input: Input, signal: AbortSignal) => Promise<Result>, debounceMs = 300) {
    return createPreviewScheduler<Input, Result>({
      load,
      debounceMs,
      onChange: snapshot => snapshots.push(snapshot),
    });
  }

  it('防抖：连续输入只发一次请求，并使用最后一次输入', async () => {
    const load = vi.fn(async (input: Input) => ({ echo: input.value }));
    const scheduler = schedulerWith(load);

    scheduler.request({ value: 'a' });
    await vi.advanceTimersByTimeAsync(100);
    scheduler.request({ value: 'ab' });
    await vi.advanceTimersByTimeAsync(100);
    scheduler.request({ value: 'abc' });

    expect(load).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(300);
    expect(load).toHaveBeenCalledTimes(1);
    expect(load.mock.calls[0][0]).toEqual({ value: 'abc' });
    expect(snapshots.at(-1)).toEqual({ status: 'ready', result: { echo: 'abc' } });
  });

  it('计算输入未变时不重复请求（名称/备注等无关字段变化不触发）', async () => {
    const load = vi.fn(async (input: Input) => ({ echo: input.value }));
    const scheduler = schedulerWith(load);

    scheduler.request({ value: 'same' });
    await vi.advanceTimersByTimeAsync(300);
    expect(load).toHaveBeenCalledTimes(1);

    scheduler.request({ value: 'same' });
    await vi.advanceTimersByTimeAsync(300);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('字段未齐时不请求并显示待填写', async () => {
    const load = vi.fn(async (input: Input) => ({ echo: input.value }));
    const scheduler = schedulerWith(load);

    scheduler.request(null);
    await vi.advanceTimersByTimeAsync(600);

    expect(load).not.toHaveBeenCalled();
    expect(snapshots.at(-1)).toEqual({ status: 'idle', result: null });
  });

  it('响应乱序：过期响应被忽略，旧结果立即失效', async () => {
    const first = deferred<Result>();
    const second = deferred<Result>();
    const signals: AbortSignal[] = [];
    const load = vi.fn((input: Input, signal: AbortSignal) => {
      signals.push(signal);
      return input.value === 'a' ? first.promise : second.promise;
    });
    const scheduler = schedulerWith(load);

    scheduler.request({ value: 'a' });
    await vi.advanceTimersByTimeAsync(300);
    scheduler.request({ value: 'b' });

    // 输入已变：旧结果作废，先显示计算中
    expect(snapshots.at(-1)).toEqual({ status: 'loading', result: null });
    expect(signals[0].aborted).toBe(true);

    await vi.advanceTimersByTimeAsync(300);
    second.resolve({ echo: 'b' });
    await vi.advanceTimersByTimeAsync(0);
    expect(snapshots.at(-1)).toEqual({ status: 'ready', result: { echo: 'b' } });

    first.resolve({ echo: 'a' });
    await vi.advanceTimersByTimeAsync(0);
    expect(snapshots.at(-1)).toEqual({ status: 'ready', result: { echo: 'b' } });
  });

  it('失败显示可重试，重试复用上次输入', async () => {
    const load = vi
      .fn<(input: Input, signal: AbortSignal) => Promise<Result>>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ echo: 'a' });
    const scheduler = schedulerWith(load);

    scheduler.request({ value: 'a' });
    await vi.advanceTimersByTimeAsync(300);
    expect(snapshots.at(-1)).toEqual({ status: 'failed', result: null });

    scheduler.retry();
    await vi.advanceTimersByTimeAsync(300);
    expect(load).toHaveBeenCalledTimes(2);
    expect(snapshots.at(-1)).toEqual({ status: 'ready', result: { echo: 'a' } });
  });

  it('保存结果覆盖预估，迟到的试算响应不能覆盖', async () => {
    const pending = deferred<Result>();
    let signal: AbortSignal | undefined;
    const load = vi.fn((_input: Input, current: AbortSignal) => {
      signal = current;
      return pending.promise;
    });
    const scheduler = schedulerWith(load);

    scheduler.request({ value: 'a' });
    await vi.advanceTimersByTimeAsync(300);
    expect(snapshots.at(-1)).toEqual({ status: 'loading', result: null });

    scheduler.showSaved({ echo: 'saved' });
    expect(snapshots.at(-1)).toEqual({ status: 'ready', result: { echo: 'saved' } });
    expect(signal?.aborted).toBe(true);

    pending.resolve({ echo: 'a' });
    await vi.advanceTimersByTimeAsync(0);
    expect(snapshots.at(-1)).toEqual({ status: 'ready', result: { echo: 'saved' } });
  });

  it('取消后不再发起请求，被取消的失败不显示为失败', async () => {
    const pending = deferred<Result>();
    const load = vi.fn(() => pending.promise);
    const scheduler = schedulerWith(load);

    scheduler.request({ value: 'a' });
    scheduler.cancel();
    await vi.advanceTimersByTimeAsync(600);
    expect(load).not.toHaveBeenCalled();

    scheduler.request({ value: 'b' });
    await vi.advanceTimersByTimeAsync(300);
    expect(load).toHaveBeenCalledTimes(1);
    scheduler.cancel();
    pending.reject(new Error('aborted'));
    await vi.advanceTimersByTimeAsync(0);
    expect(snapshots.at(-1)).toEqual({ status: 'idle', result: null });
  });
});
