import Lottie from 'react-lottie'
import animationData from 'assets/disco.json'

export default function DiscoBall({ size }: { size: number }) {
  const defaultOptions = {
    loop: true,
    autoplay: true,
    animationData: animationData,
    rendererSettings: {
      preserveAspectRatio: 'xMidYMid slice'
    }
  }

  return (
    <div>
      <Lottie options={defaultOptions} height={size} width={size} />
    </div>
  )
}
