package net.ripe.rpki.publicationserver

class Repository {

  def update(msg: QueryPdu) : ReplyPdu = {
    msg match {
      case PublishQ(uri, tag, _,  _) => PublishR(uri, tag)
      case WithdrawQ(uri, tag, _) => WithdrawR(uri, tag)
    }
  }

}
