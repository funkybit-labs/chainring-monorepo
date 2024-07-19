import { classNames } from 'utils'

export default function Input({
  disabled,
  placeholder,
  value,
  onChange,
  onBlur
}: {
  disabled: boolean
  placeholder: string
  value: string
  onChange: (v: string) => void
  onBlur?: () => void
}) {
  return (
    <input
      className={classNames(
        'block w-full rounded-xl border-0',
        'bg-darkBluishGray9 py-3 text-white',
        'disabled:text-lightBluishGray5 ',
        'ring-1 ring-inset ring-darkBluishGray6 focus:ring-1 focus:ring-inset focus:ring-mutedGray',
        '[appearance:textfield] placeholder:text-darkBluishGray2',
        '[&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none'
      )}
      disabled={disabled}
      placeholder={placeholder}
      value={value}
      onChange={(v) => onChange(v.target.value)}
      onBlur={() => {
        if (onBlur) {
          onBlur()
        }
      }}
    />
  )
}
