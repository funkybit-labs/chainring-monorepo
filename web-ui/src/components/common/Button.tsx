import { classNames } from '../../utils'

type Props = {
  caption: () => string
  onClick: () => void
  disabled: boolean
}
export function Button({ caption, onClick, disabled }: Props) {
  return (
    <button
      disabled={disabled}
      className={classNames(
        'border-transparent rounded-lg bg-darkGray p-2 px-4 text-sm font-medium focus:outline-none focus:ring-1 focus:ring-inset',
        disabled ? 'text-neutralGray' : 'text-white'
      )}
      onClick={onClick}
    >
      {caption()}
    </button>
  )
}
