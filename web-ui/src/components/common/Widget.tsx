import { classNames } from 'utils'

type Props = {
  title: string
  contents: JSX.Element
  span?: number
}

export function Widget({ title, contents, span }: Props) {
  return (
    <div className={classNames(span ? `col-span-${span}` : '')}>
      <div className="mb-2 text-center text-xl text-black">{title}</div>
      <div className="rounded-lg bg-black p-4 text-white shadow-lg">
        {contents}
      </div>
    </div>
  )
}
