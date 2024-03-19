import React, { Fragment } from 'react'
import { Dialog, Transition } from '@headlessui/react'
import { QueryObserverBaseResult } from '@tanstack/react-query'
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
          <div className="flex min-h-full items-center justify-center p-4 text-center">
            <Transition.Child
              as={Fragment}
              enter="ease-out duration-300"
              enterFrom="opacity-0 scale-95"
              enterTo="opacity-100 scale-100"
              leave="ease-in duration-200"
              leaveFrom="opacity-100 scale-100"
              leaveTo="opacity-0 scale-95"
            >
              <Dialog.Panel className="w-full max-w-md overflow-hidden rounded-lg bg-white p-6 text-left align-middle shadow-xl transition-all">
                {children}
              </Dialog.Panel>
            </Transition.Child>
          </div>
        </div>
      </Dialog>
    </Transition>
  )
}

export function ModalAsyncContent<QData>({
  query,
  success,
  error
}: {
  query: QueryObserverBaseResult<QData>
  success: (data: QData) => JSX.Element
  error: () => JSX.Element
}) {
  switch (query.status) {
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
      return success(query.data!)
  }
}
