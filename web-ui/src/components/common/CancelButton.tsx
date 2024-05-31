import { classNames } from 'utils'

export default function CancelButton({
  disabled,
  onClick,
  caption
}: {
  disabled: boolean
  onClick: () => void
  caption: () => string
}) {
  return (
    <>
      <button
        type="button"
        disabled={disabled}
        className={classNames(
          'mt-4 w-full inline-flex justify-center rounded-[50px] px-4 py-3 text-md text-white focus:outline-none focus:ring-1 focus:ring-inset',
          disabled
            ? 'bg-neutralGray focus:ring-mutedGray'
            : 'bg-neutralGray hover:bg-blue4 focus:ring-lightBackground'
        )}
        onClick={onClick}
      >
        {caption()}
      </button>
    </>
  )
}
