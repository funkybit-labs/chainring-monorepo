import { classNames } from 'utils'

type Props = {
  contents: JSX.Element
  span?: number
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  wrapperRef?: any
}

export function Widget({ contents, span, wrapperRef }: Props) {
  return (
    <div
      ref={wrapperRef}
      className={classNames(span ? `col-span-${span}` : '')}
    >
      <div className="rounded-lg bg-darkBluishGray9 p-4 text-white shadow-lg">
        {contents}
      </div>
    </div>
  )
}
