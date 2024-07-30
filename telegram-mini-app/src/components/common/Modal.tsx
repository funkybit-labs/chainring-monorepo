import React, { Fragment } from 'react'
import { Dialog, Transition } from '@headlessui/react'
import Spinner from 'components/common/Spinner'

export function Modal({
  isOpen,
  close,
  onClosed,
  children
}: {
  isOpen: boolean
  close: () => void
  onClosed: () => void
  children: JSX.Element | JSX.Element[]
}) {
  return (
    <>
      <Transition appear show={isOpen} as={Fragment}>
        <Dialog as="div" className="relative z-30" onClose={close}>
          <Dialog.Backdrop className="fixed left-0 top-0 z-20 h-screen w-screen opacity-95 backdrop-blur" />
          <Transition.Child
            as={Fragment}
            enter="ease-out duration-300"
            enterFrom="opacity-0"
            enterTo="opacity-100"
            leave="ease-in duration-200"
            leaveFrom="opacity-100"
            leaveTo="opacity-0"
            afterLeave={onClosed}
          >
            <div className="fixed inset-0 bg-black/25" />
          </Transition.Child>

          <div className="fixed inset-0 overflow-y-auto">
            <div className="flex min-h-full items-center justify-center text-center">
              <Transition.Child
                as={Fragment}
                enter="ease-out duration-300"
                enterFrom="opacity-0 scale-95"
                enterTo="opacity-100 scale-100"
                leave="ease-in duration-200"
                leaveFrom="opacity-100 scale-100"
                leaveTo="opacity-0 scale-95"
              >
                <Dialog.Panel className="mx-4 w-full overflow-hidden rounded bg-modalBlue text-left align-middle transition-all">
                  <div className="flex flex-col">
                    <span
                      className="mx-2 my-1 cursor-pointer self-end text-white"
                      onClick={() => close()}
                    >
                      X
                    </span>
                    <div className="p-5 pt-0">{children}</div>
                  </div>
                </Dialog.Panel>
              </Transition.Child>
            </div>
          </div>
        </Dialog>
      </Transition>
    </>
  )
}

export function ModalAsyncContent<T>({
  asyncData,
  success,
  error
}: {
  asyncData:
    | { status: 'pending' }
    | { status: 'error' }
    | { status: 'success'; data: T }
  success: (data: T) => JSX.Element
  error: () => JSX.Element
}) {
  switch (asyncData.status) {
    case 'pending':
      return (
        <div className="flex h-full items-center justify-center">
          <div className="size-12">
            <Spinner />
          </div>
        </div>
      )
    case 'error':
      return (
        <div className="flex h-full items-center justify-center">{error()}</div>
      )
    case 'success':
      return success(asyncData.data)
  }
}
