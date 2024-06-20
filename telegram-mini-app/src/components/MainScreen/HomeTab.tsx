import LogoHSvg from 'assets/logo-h.svg'
import CRSvg from 'assets/cr.svg'
import MillisecondsSvg from 'assets/milliseconds.svg'

export default function HomeTab() {
  return (
    <div className="flex min-h-full flex-col">
      <div>
        <div className="mx-4 flex gap-4 pt-4">
          <img src={LogoHSvg} />
          <div className="text-left text-lg font-semibold text-white">
            When every millisecond counts
          </div>
        </div>
        <div className="mr-4 mt-4">
          <img src={MillisecondsSvg} />
        </div>
      </div>
      <div className="flex grow flex-col justify-center">
        <div className="flex flex-col items-center justify-center">
          <div className="flex items-center gap-4 rounded-lg bg-darkBluishGray9 px-5 py-4 text-2xl font-bold text-white">
            <img src={CRSvg} className="size-[40px]" />
            1,019,012.063 CR
          </div>
          <div className="mt-2 text-center text-sm text-darkBluishGray2">
            Accumulated from referral link
          </div>
        </div>
      </div>
    </div>
  )
}
