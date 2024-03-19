import { FunctionComponent } from "react"

type Props = {
  caption: () => string,
  onClick: () => void
}
export function Button({caption, onClick}: Props) {
  return <button className="p-2 bg-darkGray rounded-lg border-transparent px-4 py-2 text-sm font-medium text-white focus:outline-none focus:ring-1 focus:ring-inset"
                 onClick={onClick}>
    {caption()}
  </button>
}
