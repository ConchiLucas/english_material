import { Alert, Button, Empty, Image, Pagination, Select, Skeleton } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { getImageResults, imageAssetUrl } from './api';
import type { ImageResultPage, ImageResultPageSize, ImageResultShot } from './image-story-types';

interface PageError {
  message: string;
  page: number;
  pageSize: ImageResultPageSize;
}

const pageSizes: ImageResultPageSize[] = [10, 20, 100];
const formatDate = (value: string) => new Date(value).toLocaleString('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
});
const shotSummary = (shot: ImageResultShot) => {
  if (shot.dialogue?.trim()) return `“${shot.dialogue.trim()}”`;
  if (shot.caption?.trim()) return shot.caption.trim();
  return shot.sourceExcerpt?.trim() || '最终分镜';
};

export default function ImageGeneratedResultsPage() {
  const [data, setData] = useState<ImageResultPage | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<ImageResultPageSize>(10);
  const [loading, setLoading] = useState(true);
  const [pageLoading, setPageLoading] = useState(false);
  const [error, setError] = useState<PageError | null>(null);
  const [failedAssets, setFailedAssets] = useState<Set<number>>(() => new Set());
  const requestRef = useRef(0);
  const resultTopRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async (
    targetPage: number,
    targetPageSize: ImageResultPageSize,
    initial = false,
  ) => {
    const requestId = ++requestRef.current;
    if (initial) setLoading(true);
    else setPageLoading(true);
    setError(null);
    try {
      const loaded = await getImageResults(targetPage, targetPageSize);
      if (requestId !== requestRef.current) return;
      if (loaded.totalPages > 0 && targetPage > loaded.totalPages) {
        void load(loaded.totalPages, targetPageSize);
        return;
      }
      setData(loaded);
      setPage(loaded.page);
      setPageSize(loaded.pageSize);
      setFailedAssets(new Set());
      if (!initial) resultTopRef.current?.scrollIntoView?.({ block: 'start' });
    } catch (loadError) {
      if (requestId !== requestRef.current) return;
      setError({
        message: loadError instanceof Error ? loadError.message : '图片生成结果读取失败',
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

  const markAssetFailed = (assetId: number) => {
    setFailedAssets((current) => new Set(current).add(assetId));
  };

  return (
    <section className="image-results-page" aria-label="图片生成结果">
      <header className="image-results-head" ref={resultTopRef}>
        <div>
          <span className="page-eyebrow">ENGLISH MATERIAL / IMAGE RESULTS</span>
          <h2>图片生成结果</h2>
          <p>{data ? `共 ${data.totalItems} 个完成批次` : '按故事批次浏览最终分镜成品'}</p>
        </div>
        <label className="image-results-size">
          <span>每页批次</span>
          <Select<ImageResultPageSize>
            aria-label="每页批次数量"
            value={pageSize}
            options={pageSizes.map((size) => ({ value: size, label: `${size} 批 / 页` }))}
            onChange={(size) => {
              setPageSize(size);
              void load(1, size);
            }}
          />
        </label>
      </header>

      {loading && !data ? (
        <div className="image-results-skeleton" aria-label="正在加载图片生成结果">
          <Skeleton active paragraph={{ rows: 10 }} />
          <Skeleton active paragraph={{ rows: 10 }} />
        </div>
      ) : error && !data ? (
        <Alert
          type="error"
          showIcon
          message={error.message}
          action={<Button onClick={() => void load(error.page, error.pageSize, true)}>重新加载</Button>}
        />
      ) : data?.items.length === 0 ? (
        <Empty description="还没有可展示的图片生成结果" />
      ) : (
        <>
          {error && (
            <Alert
              className="image-results-page-error"
              type="error"
              showIcon
              message={error.message}
              action={<Button onClick={() => void load(error.page, error.pageSize)}>重试当前页</Button>}
            />
          )}
          <div className={pageLoading ? 'image-results-list is-loading' : 'image-results-list'} aria-busy={pageLoading}>
            {data?.items.map((item) => {
              const title = `${item.title} · ${item.stylePresetName || '未命名画风'} · ${item.imageCount} 张 · ${item.targetGrade} · ${formatDate(item.completedAt)}`;
              return (
                <article key={item.runId} aria-label={title}>
                  <header><h3 title={title}>{title}</h3></header>
                  <Image.PreviewGroup>
                    <div className="image-results-gallery">
                      {[...item.shots].sort((left, right) => left.sequence - right.sequence || left.assetId - right.assetId).map((shot) => {
                        const label = `Scene ${shot.sceneIndex} · Shot ${shot.shotIndex}`;
                        return (
                          <figure key={shot.assetId}>
                            <div className="image-results-frame">
                              {failedAssets.has(shot.assetId) ? (
                                <div className="image-results-broken" role="img" aria-label={`${label} 图片读取失败`}>图片读取失败</div>
                              ) : (
                                <Image
                                  src={imageAssetUrl(shot.assetId)}
                                  alt={label}
                                  preview={{ mask: '查看大图' }}
                                  onError={() => markAssetFailed(shot.assetId)}
                                />
                              )}
                            </div>
                            <figcaption>
                              <strong>{label}</strong>
                              <span title={shotSummary(shot)}>{shotSummary(shot)}</span>
                            </figcaption>
                          </figure>
                        );
                      })}
                    </div>
                  </Image.PreviewGroup>
                </article>
              );
            })}
          </div>
          {!!data?.totalItems && (
            <Pagination
              className="image-results-pagination"
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
