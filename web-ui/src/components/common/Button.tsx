import { classNames } from 'utils'

type Props = {
  caption: () => JSX.Element
  onClick: () => void
  disabled: boolean
  narrow?: boolean
  primary?: boolean
}
export function Button({ caption, onClick, disabled, narrow, primary }: Props) {
  return (
    <button
      disabled={disabled}
      className={classNames(
        'overflow-ellipsis overflow-hidden border-transparent rounded-md',
        'transition-colors duration-300 ease-in-out text-darkBluishGray1',
        primary
          ? 'bg-primary4 text-white hover:bg-primary5'
          : 'bg-darkBluishGray7 text-darkBluishGray1',
        narrow ? 'px-2 my-1 mx-1' : 'px-4 py-2',
        'focus:outline-none focus:ring-1 focus:ring-inset focus:ring-mutedGray',
        disabled ? 'opacity-50' : 'hover:bg-mutedGray'
      )}
      onClick={onClick}
    >
      {caption()}
    </button>
  )
}
