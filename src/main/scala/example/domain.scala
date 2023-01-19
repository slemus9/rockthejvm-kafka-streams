package example

object domain {

  type UserId = String
  type Profile = String
  type Product = String
  type OrderId = String 

  final case class Order(
    orderId: OrderId,
    user: UserId,
    products: List[Product],
    amount: Double
  )

  final case class Discount(profile: Profile, amount: Double)

  final case class Payment(orderId: OrderId, status: String)
}