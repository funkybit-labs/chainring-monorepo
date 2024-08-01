import { classNames } from 'utils'

type Props = {
  className?: string
  disabled?: boolean
  caption: () => JSX.Element | string
  onClick: () => void
}
export function Button({ caption, disabled, onClick, className }: Props) {
  return (
    <button
      disabled={disabled}
      className={classNames(
        'overflow-ellipsis overflow-hidden border-transparent rounded-[50px]',
        'transition-colors duration-300 ease-in-out text-white',
        'bg-brightOrange text-white',
        'px-4',
        disabled && 'opacity-50',
        className
      )}
      onClick={onClick}
    >
      {caption()}
    </button>
  )
}
