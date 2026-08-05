import {
  CaretDownOutlined,
  CaretUpOutlined,
  DownOutlined,
  HistoryOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  RightOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Modal,
  Pagination,
  Select,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ConnectionConfig,
  WordCleanFacets,
  WordCleanItem,
  WordCleanQuery,
  WordCleanSentenceItem,
  getWordCleanFacets,
  getWordCleanSentences,
  getWordCleanWords,
} from './api';

const { Title, Text } = Typography;
type SortBy = NonNullable<WordCleanQuery['sortBy']>;
type SortOrder = NonNullable<WordCleanQuery['sortOrder']>;
type AudioTarget = `word:${number}` | `sentence:${number}`;
type LevelKind = 'pep' | 'source';

interface LevelItem {
  kind: LevelKind;
  value: number;
  label: string;
  count: number;
}

interface LevelGroup {
  key: string;
  title: string;
  items: LevelItem[];
}

interface WordCleanPageProps {
  connections: ConnectionConfig[];
}

const EMPTY_FACETS: WordCleanFacets = {
  pepDifficulties: [],
  sourceDifficulties: [],
  difficultyRanges: [],
};

const LEVEL_GROUPS = [
  { key: 'primary', title: '小学英语', kind: 'pep' as const, values: [1, 2, 3, 4, 5, 6, 7, 8] },
  { key: 'junior', title: '初中英语', kind: 'pep' as const, values: [9, 10, 11, 12, 13] },
  { key: 'senior', title: '高中英语', kind: 'pep' as const, values: [14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24] },
  { key: 'college', title: '大学英语', kind: 'source' as const, values: [25, 28] },
  { key: 'entrance', title: '升学考试英语', kind: 'source' as const, values: [26] },
  { key: 'business-abroad', title: '商务与出国英语', kind: 'source' as const, values: [27, 29, 32, 33] },
  { key: 'professional', title: '专业英语', kind: 'source' as const, values: [30, 31] },
  { key: 'advanced', title: '高阶考试英语', kind: 'source' as const, values: [34, 35] },
  { key: 'other', title: '其他来源', kind: 'source' as const, values: [36] },
];

const SOURCE_LABELS: Record<number, string> = {
  25: '四级', 26: '考研', 27: 'BEC', 28: '六级', 29: '雅思', 30: '专四级',
  31: '专八级', 32: '托福', 33: 'GMAT', 34: 'SAT', 35: 'GRE', 36: '其他词库',
};

function levelLabel(kind: LevelKind, value: number, label: string) {
  if (kind === 'source' && SOURCE_LABELS[value]) return SOURCE_LABELS[value];
  return label
    .replace(/^\d+\.\s*/, '')
    .replace(/^(小学英语|初中英语|高中英语)/, '')
    .trim() || label;
}

function groupLevelFacets(facets: WordCleanFacets): LevelGroup[] {
  const pep = new Map(facets.pepDifficulties.map((item) => [Number(item.value), item]));
  const source = new Map(facets.sourceDifficulties.map((item) => [Number(item.value), item]));
  return LEVEL_GROUPS.map((group) => ({
    key: group.key,
    title: group.title,
    items: group.values.flatMap((value) => {
      const facet = (group.kind === 'pep' ? pep : source).get(value);
      return facet ? [{
        kind: group.kind,
        value,
        label: levelLabel(group.kind, value, facet.label),
        count: facet.count,
      }] : [];
    }),
  })).filter((group) => group.items.length > 0);
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。';
}

function playableAudio(status: string, url: string) {
  return status === 'success' && url.trim() ? url.trim() : '';
}

function formatTime(value: string) {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export default function WordCleanPage({ connections }: WordCleanPageProps) {
  const preferredConnection = useMemo(
    () => connections.find((item) => /rob[_-]?english[_-]?word/i.test(`${item.connectionName} ${item.databaseName}`)) ?? connections[0],
    [connections],
  );
  const [connectionId, setConnectionId] = useState<number>();
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pepDifficulty, setPepDifficulty] = useState<number>();
  const [sourceDifficulty, setSourceDifficulty] = useState<number>();
  const [difficultyRange, setDifficultyRange] = useState<string>();
  const [sortBy, setSortBy] = useState<SortBy>();
  const [sortOrder, setSortOrder] = useState<SortOrder>('asc');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [reloadToken, setReloadToken] = useState(0);
  const [items, setItems] = useState<WordCleanItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [facets, setFacets] = useState<WordCleanFacets>(EMPTY_FACETS);
  const [facetsLoading, setFacetsLoading] = useState(false);
  const [facetError, setFacetError] = useState('');
  const [expandedLevelGroups, setExpandedLevelGroups] = useState<Set<string>>(
    () => new Set(LEVEL_GROUPS.map((group) => group.key)),
  );
  const [selectedWord, setSelectedWord] = useState<WordCleanItem>();
  const [sentences, setSentences] = useState<WordCleanSentenceItem[]>([]);
  const [sentencesLoading, setSentencesLoading] = useState(false);
  const [sentencesError, setSentencesError] = useState('');
  const audioRef = useRef<HTMLAudioElement>();
  const [playingTarget, setPlayingTarget] = useState<AudioTarget>();
  const [loadingTarget, setLoadingTarget] = useState<AudioTarget>();

  useEffect(() => {
    if (connectionId === undefined && preferredConnection?.ID !== undefined) {
      setConnectionId(preferredConnection.ID);
    }
  }, [connectionId, preferredConnection]);

  useEffect(() => {
    if (connectionId === undefined) {
      setFacets(EMPTY_FACETS);
      return;
    }
    let active = true;
    setFacetsLoading(true);
    setFacetError('');
    void getWordCleanFacets(connectionId)
      .then((result) => {
        if (active) setFacets(result);
      })
      .catch((nextError) => {
        if (active) {
          setFacets(EMPTY_FACETS);
          setFacetError(errorMessage(nextError));
        }
      })
      .finally(() => {
        if (active) setFacetsLoading(false);
      });
    return () => {
      active = false;
    };
  }, [connectionId]);

  const selectedRange = useMemo(
    () => facets.difficultyRanges.find((item) => item.value === difficultyRange),
    [difficultyRange, facets.difficultyRanges],
  );
  const levelGroups = useMemo(() => groupLevelFacets(facets), [facets]);

  const toggleLevelGroup = (key: string) => {
    setExpandedLevelGroups((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const selectLevel = (item: LevelItem) => {
    stopAudio();
    if (item.kind === 'pep') {
      setPepDifficulty(item.value);
      setSourceDifficulty(undefined);
      setSortBy('pepDifficulty');
    } else {
      setSourceDifficulty(item.value);
      setPepDifficulty(undefined);
      setSortBy('sourceDifficulty');
    }
    setSortOrder('asc');
    setPage(1);
  };

  useEffect(() => {
    if (connectionId === undefined) {
      setItems([]);
      setTotal(0);
      setError('');
      return;
    }
    let active = true;
    const [difficultyMin, difficultyMax] = selectedRange?.value.split('-').map(Number) ?? [];
    setLoading(true);
    setError('');
    void getWordCleanWords({
      connectionId,
      keyword,
      pepDifficulty,
      sourceDifficulty,
      difficultyMin,
      difficultyMax,
      sortBy,
      sortOrder,
      page,
      pageSize,
    })
      .then((result) => {
        if (!active) return;
        setItems(result.list);
        setTotal(result.total);
      })
      .catch((nextError) => {
        if (!active) return;
        setItems([]);
        setTotal(0);
        setError(errorMessage(nextError));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [connectionId, keyword, pepDifficulty, sourceDifficulty, selectedRange, sortBy, sortOrder, page, pageSize, reloadToken]);

  useEffect(() => {
    if (!selectedWord || connectionId === undefined) {
      setSentences([]);
      return;
    }
    let active = true;
    setSentencesLoading(true);
    setSentencesError('');
    void getWordCleanSentences(connectionId, selectedWord.id)
      .then((result) => {
        if (active) setSentences(result);
      })
      .catch((nextError) => {
        if (active) {
          setSentences([]);
          setSentencesError(errorMessage(nextError));
        }
      })
      .finally(() => {
        if (active) setSentencesLoading(false);
      });
    return () => {
      active = false;
    };
  }, [connectionId, selectedWord]);

  useEffect(() => () => {
    audioRef.current?.pause();
    audioRef.current = undefined;
  }, []);

  const stopAudio = () => {
    audioRef.current?.pause();
    audioRef.current = undefined;
    setPlayingTarget(undefined);
    setLoadingTarget(undefined);
  };

  const playAudio = (url: string, target: AudioTarget) => {
    if (!url) return;
    if (playingTarget === target || loadingTarget === target) {
      stopAudio();
      return;
    }
    stopAudio();
    const audio = new Audio(url);
    audioRef.current = audio;
    setLoadingTarget(target);
    audio.addEventListener('playing', () => {
      setLoadingTarget(undefined);
      setPlayingTarget(target);
    });
    audio.addEventListener('ended', stopAudio, { once: true });
    audio.addEventListener('error', stopAudio, { once: true });
    void audio.play().catch(stopAudio);
  };

  const changeSort = (nextSortBy: SortBy) => {
    if (sortBy === nextSortBy) {
      setSortOrder((current) => current === 'asc' ? 'desc' : 'asc');
    } else {
      setSortBy(nextSortBy);
      setSortOrder('asc');
    }
    setPage(1);
  };

  const sortTitle = (label: string, value: SortBy) => {
    const active = sortBy === value;
    return (
      <button
        type="button"
        className={active ? 'word-sort-button active' : 'word-sort-button'}
        aria-label={`${label}${active && sortOrder === 'desc' ? '降序' : '升序'}排列`}
        onClick={() => changeSort(value)}
      >
        <span>{label}</span>
        {active && sortOrder === 'desc' ? <CaretDownOutlined /> : <CaretUpOutlined />}
      </button>
    );
  };

  const columns: TableColumnsType<WordCleanItem> = [
    {
      title: '序号',
      key: 'index',
      width: 72,
      fixed: 'left',
      render: (_value, _item, index) => (page - 1) * pageSize + index + 1,
    },
    {
      title: '单词',
      dataIndex: 'word',
      key: 'word',
      width: 190,
      fixed: 'left',
      render: (value: string, item) => {
        const audioUrl = playableAudio(item.wordTtsStatus, item.wordTtsObjectUrl);
        const target = `word:${item.id}` as const;
        return (
          <div className="word-cell">
            <Tooltip title={value}><strong className="word-table-ellipsis">{value}</strong></Tooltip>
            <Tooltip title={audioUrl ? '播放单词发音' : '暂无可播放发音'}>
              <Button
                type="text"
                size="small"
                disabled={!audioUrl}
                loading={loadingTarget === target}
                icon={playingTarget === target ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
                aria-label={`${playingTarget === target ? '停止' : '播放'} ${value} 的单词发音`}
                onClick={() => playAudio(audioUrl, target)}
              />
            </Tooltip>
            <Tooltip title="查看造句结果">
              <Button
                type="text"
                size="small"
                icon={<HistoryOutlined />}
                aria-label={`查看 ${value} 的造句结果`}
                onClick={() => setSelectedWord(item)}
              />
            </Tooltip>
          </div>
        );
      },
    },
    {
      title: '中文释义',
      dataIndex: 'meaning',
      key: 'meaning',
      width: 220,
      render: (value: string) => <Tooltip title={value}><span className="word-table-ellipsis">{value || '-'}</span></Tooltip>,
    },
    { title: sortTitle('难度', 'difficulty'), dataIndex: 'difficulty', key: 'difficulty', width: 100 },
    { title: sortTitle('频率', 'frequency'), dataIndex: 'frequency', key: 'frequency', width: 100 },
    {
      title: sortTitle('教材难度', 'pepDifficulty'),
      key: 'pepDifficulty',
      width: 160,
      render: (_value, item) => item.pepDifficultyLabel || '-',
    },
    {
      title: sortTitle('来源难度', 'sourceDifficulty'),
      key: 'sourceDifficulty',
      width: 180,
      render: (_value, item) => item.sourceDifficulty ? `${item.sourceDifficulty}. ${item.sourceLabel}` : '-',
    },
    {
      title: '最佳例句',
      key: 'sentence',
      width: 360,
      render: (_value, item) => {
        const sentence = item.bestSentence || item.sentence || '-';
        const audioUrl = playableAudio(item.bestSentenceTtsStatus, item.bestSentenceTtsObjectUrl);
        const target = `sentence:${item.id}` as const;
        return (
          <div className="sentence-cell">
            <Tooltip title={sentence}><span className="word-table-ellipsis">{sentence}</span></Tooltip>
            <Tooltip title={audioUrl ? '播放例句语音' : '暂无可播放语音'}>
              <Button
                type="text"
                size="small"
                disabled={!audioUrl}
                loading={loadingTarget === target}
                icon={playingTarget === target ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
                aria-label={`${playingTarget === target ? '停止' : '播放'} ${item.word} 的例句语音`}
                onClick={() => playAudio(audioUrl, target)}
              />
            </Tooltip>
            {item.bestSentenceScore !== undefined && (
              <Tooltip title={[item.bestSentenceScoreReason, item.bestSentenceScoreModelName, formatTime(item.bestSentenceScoredAt)].filter(Boolean).join(' · ')}>
                <Tag color="blue">{item.bestSentenceScore} 分</Tag>
              </Tooltip>
            )}
          </div>
        );
      },
    },
    {
      title: '操作',
      key: 'actions',
      width: 90,
      fixed: 'right',
      render: (_value, item) => <Button type="link" onClick={() => setSelectedWord(item)}>结果</Button>,
    },
  ];

  const clearLevelFilters = () => {
    stopAudio();
    setPepDifficulty(undefined);
    setSourceDifficulty(undefined);
    setSortBy(undefined);
    setSortOrder('asc');
    setPage(1);
  };

  const clearFilters = () => {
    stopAudio();
    setKeywordInput('');
    setKeyword('');
    setPepDifficulty(undefined);
    setSourceDifficulty(undefined);
    setDifficultyRange(undefined);
    setSortBy(undefined);
    setSortOrder('asc');
    setPage(1);
  };

  if (!connections.some((item) => item.ID !== undefined)) {
    return (
      <section className="panel-page" aria-label="去重单词表">
        <Card className="empty-panel">
          <Empty description="请先在数据库配置中新增数据源" />
        </Card>
      </section>
    );
  }

  return (
    <section className="panel-page word-clean-page" aria-label="去重单词表">
      <div className="word-browser-layout">
        <aside className="word-level-panel" aria-label="英语难度目录">
          <div className="word-level-heading">
            <div>
              <Text strong>英语难度目录</Text>
              <Text type="secondary">选择教材册次或考试词库</Text>
            </div>
            {(pepDifficulty || sourceDifficulty) && <Button type="link" size="small" onClick={clearLevelFilters}>全部</Button>}
          </div>
          <div className="word-level-groups">
            {facetsLoading ? (
              <div className="word-level-state"><Spin size="small" /></div>
            ) : facetError ? (
              <Alert type="warning" showIcon message="目录读取失败" description={facetError} />
            ) : levelGroups.length ? levelGroups.map((group) => {
              const expanded = expandedLevelGroups.has(group.key);
              const groupCount = group.items.reduce((sum, item) => sum + item.count, 0);
              return (
                <section className="word-level-group" key={group.key}>
                  <button
                    type="button"
                    className="word-level-group-toggle"
                    aria-expanded={expanded}
                    onClick={() => toggleLevelGroup(group.key)}
                  >
                    {expanded ? <DownOutlined /> : <RightOutlined />}
                    <span>{group.title}</span>
                    <small>{groupCount}词</small>
                  </button>
                  {expanded && (
                    <div className="word-level-items">
                      {group.items.map((item) => {
                        const active = item.kind === 'pep'
                          ? pepDifficulty === item.value
                          : sourceDifficulty === item.value;
                        return (
                          <button
                            type="button"
                            className={active ? 'active' : ''}
                            aria-pressed={active}
                            key={`${item.kind}-${item.value}`}
                            onClick={() => selectLevel(item)}
                          >
                            <span>{item.label}</span>
                            <small>{item.count}词</small>
                          </button>
                        );
                      })}
                    </div>
                  )}
                </section>
              );
            }) : (
              <div className="word-level-state"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无难度目录" /></div>
            )}
          </div>
        </aside>

        <div className="word-main-panel">
          <Card className="word-filter-panel">
            <div className="word-filter-grid">
              <Input
                allowClear
                prefix={<SearchOutlined />}
                placeholder="搜索单词、释义、例句或难度标签"
                value={keywordInput}
                onChange={(event) => setKeywordInput(event.target.value)}
                onPressEnter={() => {
                  setKeyword(keywordInput.trim());
                  setPage(1);
                }}
              />
              <Select
                allowClear
                placeholder="数值难度"
                value={difficultyRange}
                onChange={(value) => { setDifficultyRange(value); setPage(1); }}
                options={facets.difficultyRanges.map((item) => ({ value: item.value, label: `${item.label} · ${item.count}词` }))}
              />
              <Button type="primary" icon={<SearchOutlined />} onClick={() => { setKeyword(keywordInput.trim()); setPage(1); }}>
                搜索
              </Button>
              <Button onClick={clearFilters}>清除筛选</Button>
            </div>
          </Card>

          {error ? (
            <Alert
              className="word-clean-error"
              type="error"
              showIcon
              message="去重单词读取失败"
              description={error}
              action={<Button onClick={() => setReloadToken((value) => value + 1)}>重试</Button>}
            />
          ) : (
            <Card className="word-table-panel">
              <div className="word-table-summary">
                <div>
                  <Text strong>单词基础表</Text>
                  <Text type="secondary">只读查询，不会修改所选数据源</Text>
                </div>
                <Text type="secondary">共 {total} 条单词</Text>
              </div>
              <Table<WordCleanItem>
                columns={columns}
                dataSource={items}
                rowKey="id"
                loading={loading}
                pagination={false}
                scroll={{ x: 1550 }}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的单词" /> }}
              />
              {total > 0 && (
                <Pagination
                  className="word-pagination"
                  current={page}
                  pageSize={pageSize}
                  total={total}
                  showSizeChanger
                  showQuickJumper
                  pageSizeOptions={[20, 50, 100, 500]}
                  showTotal={(value) => `共 ${value} 条单词`}
                  onChange={(nextPage, nextPageSize) => {
                    setPage(nextPageSize === pageSize ? nextPage : 1);
                    setPageSize(nextPageSize);
                  }}
                />
              )}
            </Card>
          )}
        </div>
      </div>

      <Modal
        title="大模型造句结果"
        open={Boolean(selectedWord)}
        footer={null}
        width={760}
        destroyOnHidden
        onCancel={() => setSelectedWord(undefined)}
      >
        <div className="word-sentence-summary">
          <Title level={3}>{selectedWord?.word}</Title>
          <Text type="secondary">{selectedWord?.meaning || '-'}</Text>
        </div>
        {sentencesLoading ? (
          <div className="word-sentence-loading"><Spin /></div>
        ) : sentencesError ? (
          <Alert type="error" showIcon message="造句结果读取失败" description={sentencesError} />
        ) : sentences.length ? (
          <div className="word-sentence-list">
            {sentences.map((item) => (
              <article className="word-sentence-row" key={item.id}>
                <div className="word-sentence-row-head">
                  <Tag>{item.modelName || '未知模型'}</Tag>
                  {item.score !== undefined ? (
                    <Tooltip title={[item.scoreReason, item.scoreModelName, formatTime(item.scoredAt)].filter(Boolean).join(' · ')}>
                      <Tag color="blue">{item.score} 分</Tag>
                    </Tooltip>
                  ) : <Text type="secondary">未评分</Text>}
                </div>
                <p>{item.sentence || '-'}</p>
                <p className="word-sentence-translation">{item.sentenceTranslation || '-'}</p>
                {item.scoreReason && <p className="word-sentence-reason">{item.scoreReason}</p>}
              </article>
            ))}
          </div>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无造句结果" />
        )}
      </Modal>
    </section>
  );
}
