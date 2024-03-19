import { FunctionComponent } from 'react'

type Props = {
  caption: () => string
  onClick: () => void
}
export function Button({ caption, onClick }: Props) {
  return (
    <button
      className="border-transparent rounded-lg bg-darkGray p-2 px-4 text-sm font-medium text-white focus:outline-none focus:ring-1 focus:ring-inset"
      onClick={onClick}
    >
      {caption()}
    </button>
  )
}
