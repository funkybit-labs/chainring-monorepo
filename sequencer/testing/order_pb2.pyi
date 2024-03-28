from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Order(_message.Message):
    __slots__ = ("guid", "market", "orderType", "address", "amount", "price")
    class OrderType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        MarketBuy: _ClassVar[Order.OrderType]
        MarketSell: _ClassVar[Order.OrderType]
        LimitBuy: _ClassVar[Order.OrderType]
        LimitSell: _ClassVar[Order.OrderType]
    MarketBuy: Order.OrderType
    MarketSell: Order.OrderType
    LimitBuy: Order.OrderType
    LimitSell: Order.OrderType
    GUID_FIELD_NUMBER: _ClassVar[int]
    MARKET_FIELD_NUMBER: _ClassVar[int]
    ORDERTYPE_FIELD_NUMBER: _ClassVar[int]
    ADDRESS_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_FIELD_NUMBER: _ClassVar[int]
    PRICE_FIELD_NUMBER: _ClassVar[int]
    guid: str
    market: str
    orderType: Order.OrderType
    address: str
    amount: str
    price: float
    def __init__(self, guid: _Optional[str] = ..., market: _Optional[str] = ..., orderType: _Optional[_Union[Order.OrderType, str]] = ..., address: _Optional[str] = ..., amount: _Optional[str] = ..., price: _Optional[float] = ...) -> None: ...

class Orders(_message.Message):
    __slots__ = ("orders",)
    ORDERS_FIELD_NUMBER: _ClassVar[int]
    orders: _containers.RepeatedCompositeFieldContainer[Order]
    def __init__(self, orders: _Optional[_Iterable[_Union[Order, _Mapping]]] = ...) -> None: ...

class OrderResponse(_message.Message):
    __slots__ = ("guid", "disposition", "sequence", "processingTime")
    class OrderDisposition(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        Filled: _ClassVar[OrderResponse.OrderDisposition]
        PartiallyFilled: _ClassVar[OrderResponse.OrderDisposition]
        Accepted: _ClassVar[OrderResponse.OrderDisposition]
        Rejected: _ClassVar[OrderResponse.OrderDisposition]
        Failed: _ClassVar[OrderResponse.OrderDisposition]
    Filled: OrderResponse.OrderDisposition
    PartiallyFilled: OrderResponse.OrderDisposition
    Accepted: OrderResponse.OrderDisposition
    Rejected: OrderResponse.OrderDisposition
    Failed: OrderResponse.OrderDisposition
    GUID_FIELD_NUMBER: _ClassVar[int]
    DISPOSITION_FIELD_NUMBER: _ClassVar[int]
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    PROCESSINGTIME_FIELD_NUMBER: _ClassVar[int]
    guid: str
    disposition: OrderResponse.OrderDisposition
    sequence: int
    processingTime: int
    def __init__(self, guid: _Optional[str] = ..., disposition: _Optional[_Union[OrderResponse.OrderDisposition, str]] = ..., sequence: _Optional[int] = ..., processingTime: _Optional[int] = ...) -> None: ...
