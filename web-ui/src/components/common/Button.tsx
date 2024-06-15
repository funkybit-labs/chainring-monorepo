import { classNames } from 'utils'
import { Tooltip } from 'components/common/Tooltip'

export type ButtonStyle = 'normal' | 'warning'
export type ButtonWidth = 'normal' | 'narrow' | 'full'

type Props = {
  caption: () => JSX.Element | string
  onClick: () => void
  disabled: boolean
  style: ButtonStyle
  width: ButtonWidth
  primary?: boolean
  tooltip?: string
}
export function Button({
  caption,
  onClick,
  disabled,
  style,
  width,
  primary,
  tooltip
}: Props) {
  function button_() {
    return (
      <button
        disabled={disabled}
        className={classNames(
          'overflow-ellipsis overflow-hidden border-transparent rounded-[20px]',
          'transition-colors duration-300 ease-in-out text-darkBluishGray1',
          primary
            ? 'bg-primary5 text-white hover:bg-blue5'
            : 'bg-darkBluishGray7 text-darkBluishGray1',
          width == 'narrow' ? 'px-2 my-1 mx-1' : 'px-4 py-2',
          width == 'full' && 'w-full',
          'focus:outline-none focus:ring-1 focus:ring-inset focus:ring-mutedGray',
          disabled ? 'opacity-50' : 'hover:bg-primary4',
          style == 'warning' &&
            'bg-statusYellow text-darkBluishGray10 hover:bg-statusYellow hover:bg-opacity-80'
        )}
        onClick={onClick}
      >
        {caption()}
      </button>
    )
  }
  return tooltip ? (
    <Tooltip
      message={tooltip}
      style={style === 'warning' ? 'warning' : 'normal'}
    >
      {button_()}
    </Tooltip>
  ) : (
    button_()
  )
}
