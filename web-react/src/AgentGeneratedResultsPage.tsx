import { Alert, App as AntApp, Button, Empty, Pagination, Select, Skeleton } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { getStoryResults } from './api';
import type { StoryResultPage, StoryResultPageSize } from './story-flow-types';

interface PageError {
  message: string;
  page: number;
  pageSize: StoryResultPageSize;
}

const pageSizes: StoryResultPageSize[] = [10, 20, 100];
const formatDate = (value: string) => new Date(value).toLocaleString('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
});

export default function AgentGeneratedResultsPage() {
  const { message } = AntApp.useApp();
  const [data, setData] = useState<StoryResultPage | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<StoryResultPageSize>(10);
  const [loading, setLoading] = useState(true);
  const [pageLoading, setPageLoading] = useState(false);
  const [error, setError] = useState<PageError | null>(null);
  const requestRef = useRef(0);
  const resultTopRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async (
    targetPage: number,
    targetPageSize: StoryResultPageSize,
    initial = false,
  ) => {
    const requestId = ++requestRef.current;
    if (initial) setLoading(true);
    else setPageLoading(true);
    setError(null);
    try {
      const loaded = await getStoryResults(targetPage, targetPageSize);
      if (requestId !== requestRef.current) return;
      if (loaded.totalPages > 0 && targetPage > loaded.totalPages) {
        void load(loaded.totalPages, targetPageSize);
        return;
      }
      setData(loaded);
      setPage(loaded.page);
      setPageSize(loaded.pageSize);
      if (!initial) resultTopRef.current?.scrollIntoView?.({ block: 'start' });
    } catch (loadError) {
      if (requestId !== requestRef.current) return;
      setError({
        message: loadError instanceof Error ? loadError.message : 'Agent 生成结果读取失败',
        page: targetPage,
        pageSize: targetPageSize,
      });
    } finally {
      if (requestId === requestRef.current) {
        setLoading(false);
        setPageLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void load(1, 10, true);
    return () => { requestRef.current += 1; };
  }, [load]);

  const copyStory = async (title: string, story: string) => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable');
      await navigator.clipboard.writeText(story);
      message.success(`已复制《${title}》`);
    } catch {
      message.error('复制失败，请检查浏览器剪贴板权限');
    }
  };

  return (
    <section className="agent-results-page" aria-label="Agent 生成结果">
      <header className="agent-results-head" ref={resultTopRef}>
        <div>
          <span className="page-eyebrow">ENGLISH MATERIAL / RESULTS</span>
          <h2>Agent 生成结果</h2>
          <p>{data ? `共 ${data.totalItems} 条` : '浏览已完成批次的最终故事'}</p>
        </div>
        <label className="agent-results-size">
          <span>每页数量</span>
          <Select<StoryResultPageSize>
            aria-label="每页数量"
            value={pageSize}
            options={pageSizes.map((size) => ({ value: size, label: `${size} 条 / 页` }))}
            onChange={(size) => {
              setPageSize(size);
              void load(1, size);
            }}
          />
        </label>
      </header>

      {loading && !data ? (
        <div className="agent-results-skeleton" aria-label="正在加载 Agent 生成结果">
          <Skeleton active paragraph={{ rows: 8 }} />
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      ) : error && !data ? (
        <Alert
          type="error"
          showIcon
          message={error.message}
          action={<Button onClick={() => void load(error.page, error.pageSize, true)}>重新加载</Button>}
        />
      ) : data?.items.length === 0 ? (
        <Empty description="还没有可展示的 Agent 生成结果" />
      ) : (
        <>
          {error && (
            <Alert
              className="agent-results-page-error"
              type="error"
              showIcon
              message={error.message}
              action={<Button onClick={() => void load(error.page, error.pageSize)}>重试当前页</Button>}
            />
          )}
          <div className={pageLoading ? 'agent-results-list is-loading' : 'agent-results-list'} aria-busy={pageLoading}>
            {data?.items.map((item) => {
              const title = `${item.title} · ${item.targetGrade} · ${item.wordCount} 个单词 · ${formatDate(item.createdAt)}`;
              return (
                <article key={item.runId} aria-label={title}>
                  <header>
                    <h3 title={title}>{title}</h3>
                    <Button size="small" aria-label={`复制故事 ${item.title}`} onClick={() => void copyStory(item.title, item.finalStory)}>复制全文</Button>
                  </header>
                  <pre>{item.finalStory}</pre>
                </article>
              );
            })}
          </div>
          {!!data?.totalItems && (
            <Pagination
              className="agent-results-pagination"
              current={page}
              pageSize={pageSize}
              total={data.totalItems}
              showSizeChanger={false}
              hideOnSinglePage
              onChange={(nextPage) => void load(nextPage, pageSize)}
            />
          )}
        </>
      )}
    </section>
  );
}
