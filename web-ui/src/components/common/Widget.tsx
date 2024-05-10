import { classNames } from 'utils'

type Props = {
  contents: JSX.Element
  span?: number
}

export function Widget({ contents, span }: Props) {
  return (
    <div className={classNames(span ? `col-span-${span}` : '')}>
      <div className="rounded-lg bg-darkBluishGray9 p-4 text-white shadow-lg">
        {contents}
      </div>
    </div>
  )
}
