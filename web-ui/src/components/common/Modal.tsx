import React, { Fragment } from 'react'
import { Dialog, Transition } from '@headlessui/react'
import Spinner from 'components/common/Spinner'

export function Modal({
  isOpen,
  close,
  onClosed,
  children,
  title
}: {
  isOpen: boolean
  close: () => void
  onClosed: () => void
  children: JSX.Element | JSX.Element[]
  title: string
}) {
  return (
    <>
      <div className="fixed left-0 top-0 h-screen w-screen bg-darkGray opacity-65"></div>
      <Transition appear show={isOpen} as={Fragment}>
        <Dialog as="div" className="relative z-10" onClose={close}>
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
                <Dialog.Panel className="min-h-96 w-full max-w-lg overflow-hidden rounded-lg bg-lightBackground text-left align-middle shadow-xl transition-all">
                  <Dialog.Title
                    as="h3"
                    className="p-2 text-center text-lg font-medium leading-6 text-darkGray"
                  >
                    {title}
                  </Dialog.Title>
                  <hr className="text-neutralGray" />
                  <div className="p-6">{children}</div>
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
