import { classNames } from 'utils'

type Props = {
  caption: () => JSX.Element
  onClick: () => void
  disabled: boolean
}
export function Button({ caption, onClick, disabled }: Props) {
  return (
    <button
      disabled={disabled}
      className={classNames(
        'border-transparent rounded-lg bg-darkGray p-2 px-4 text-sm font-medium focus:outline-none focus:ring-1 focus:ring-inset focus:ring-mutedGray',
        disabled ? 'text-neutralGray' : 'text-white hover:bg-mutedGray'
      )}
      onClick={onClick}
    >
      {caption()}
    </button>
  )
}
