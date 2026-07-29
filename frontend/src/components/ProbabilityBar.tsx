/** 확률을 막대로 시각화한다. 수치(%)는 항상 옆에 함께 표기되므로 막대는 장식이다. */
export default function ProbabilityBar({ probability }: { probability: number }) {
  const percent = Math.min(1, Math.max(0, probability)) * 100
  return (
    <div className="prob-track" aria-hidden="true">
      <div className="prob-fill" style={{ width: `${percent}%` }} />
    </div>
  )
}
