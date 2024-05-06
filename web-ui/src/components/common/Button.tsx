import { classNames } from 'utils'

type Props = {
  caption: () => JSX.Element
  onClick: () => void
  disabled: boolean
  narrow?: boolean
}
export function Button({ caption, onClick, disabled, narrow }: Props) {
  return (
    <button
      disabled={disabled}
      className={classNames(
        'overflow-ellipsis overflow-hidden border-transparent rounded-lg bg-darkGray',
        narrow ? 'px-2 my-1 mx-1' : 'px-4 py-2',
        'font-medium focus:outline-none focus:ring-1 focus:ring-inset focus:ring-mutedGray',
        disabled ? 'text-neutralGray' : 'text-white hover:bg-mutedGray'
      )}
      onClick={onClick}
    >
      {caption()}
    </button>
  )
}
