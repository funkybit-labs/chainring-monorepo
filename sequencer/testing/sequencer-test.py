import grpc
from gateway_pb2_grpc import GatewayStub
from order_pb2 import Order
from time import time

channel = grpc.insecure_channel('test-sequencer.funkybit.fun:5337')
stub = GatewayStub(channel)
order = Order(guid = "guid", market = "market", orderType = Order.MarketBuy, address = "address", amount = "0x123", price = 43.2)

def timeAddOrder(n):
  println(f"Timing {n} AddOrders")
  t1 = time()
  for _ in range(n):
    stub.AddOrder(order)
  t2 = time()
  println(f"Total {t2 - t1}, {(t2 - t1) / n} avg")


timeAddOrder(1)
timeAddOrder(10)
timeAddOrder(100)
timeAddOrder(1000)
timeAddOrder(10000)
timeAddOrder(100000)
