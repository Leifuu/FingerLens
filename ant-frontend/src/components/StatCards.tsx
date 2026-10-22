import { Card, Col, Row, Statistic } from 'antd';

export function StatCards({ stats }: { stats: { changed: number; same: number; oneSide: number; unavailable: number } }) {
  const cards = [
    { key: 'changed', title: '值 / 状态不同', value: stats.changed, color: '#ff7875' },
    { key: 'same', title: '原值相同', value: stats.same, color: '#73d13d' },
    { key: 'oneSide', title: '单侧存在', value: stats.oneSide, color: '#597ef7' },
    { key: 'unavailable', title: '不可比较', value: stats.unavailable, color: '#8c8c8c' },
  ];
  return <Row gutter={[16, 16]} className="stat-cards">{cards.map(card => <Col xs={12} lg={6} key={card.key}><Card size="small"><Statistic title={card.title} value={card.value} valueStyle={{ color: card.color }} suffix="项" /></Card></Col>)}</Row>;
}
