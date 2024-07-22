import { classNames } from 'utils'
import { JSX } from 'react'

export type SubmitStatus = 'idle' | 'pending' | 'success' | 'error'

export default function SubmitButton({
  disabled,
  onClick,
  caption,
  error,
  status,
  className
}: {
  disabled: boolean
  onClick: () => void
  caption: () => string | JSX.Element
  error: string | null | undefined
  status: SubmitStatus
  className?: string
}) {
  return (
    <>
      <button
        type="button"
        disabled={disabled}
        className={classNames(
          'mt-4 w-full inline-flex justify-center rounded-[50px] px-4 py-3 text-md text-white focus:outline-none focus:ring-1 focus:ring-inset',
          status === 'success'
            ? 'bg-statusGreen'
            : status === 'error'
              ? 'bg-statusRed hover:bg-statusRed'
              : disabled
                ? 'bg-neutralGray focus:ring-mutedGray'
                : 'bg-primary5 hover:bg-primary5 focus:ring-lightBackground',
          className
        )}
        onClick={onClick}
      >
        {caption()}
      </button>

      {error && (
        <div className="mt-2 text-center text-sm font-bold text-brightRed">
          {error}
        </div>
      )}
    </>
  )
}
