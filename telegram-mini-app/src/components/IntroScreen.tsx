import { Button } from 'components/common/Button'
import LogoVSvg from 'assets/logo-v.svg'
import MillisecondsSvg from 'assets/milliseconds.svg'

export default function IntroScreen({
  onStartButtonClick
}: {
  onStartButtonClick: () => void
}) {
  return (
    <div className="flex h-screen flex-col justify-center gap-12">
      <div className="flex flex-col items-center justify-center">
        <img src={LogoVSvg} />
        <img src={MillisecondsSvg} className="my-8 mr-8" />
        <div className="mx-8 text-center text-3xl font-semibold text-white">
          When every millisecond counts
        </div>
      </div>
      <div className="mb-6 flex flex-col px-8">
        <div className="mb-4 text-center text-darkBluishGray2">
          Join our community and earn CR Points!
        </div>
        <Button
          caption={() => (
            <div className="py-3 text-lg font-semibold">Lets get started</div>
          )}
          onClick={onStartButtonClick}
        />
      </div>
    </div>
  )
}
