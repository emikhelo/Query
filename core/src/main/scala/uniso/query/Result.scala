package uniso.query

import java.sql.ResultSet
import java.sql.ResultSetMetaData

class Result private[query] (rs: ResultSet, cols: Vector[Column], reusableStatement: Boolean)
  extends Iterator[RowLike] with RowLike {
  private[this] val md = rs.getMetaData
  private[this] val colMap = cols.filter(_.name != null).map(c => (c.name, c)).toMap
  private[this] val row = new Array[Any](cols.length)
  private[this] var hn = true; private[this] var flag = true
  def hasNext = {
    if (hn && flag) {
      hn = rs.next; flag = false
      if (hn) {
        var i = 0
        cols foreach { c => if (c.expr != null) row(i) = c.expr(); i += 1 }
      }
    }
    hn
  }
  def next = { flag = true; this }
  def apply(columnIndex: Int) = {
    if (cols(columnIndex).idx != -1) asAny(cols(columnIndex).idx)
    else row(columnIndex)
  }
  def apply(columnLabel: String) = {
    try {
      colMap(columnLabel) match {
        case Column(i, _, null) => asAny(i)
        case Column(i, _, e) => row(i)
      }
    } catch { case _: NoSuchElementException => asAny(rs.findColumn(columnLabel)) }
  }
  def columnCount = cols.length
  def column(idx: Int) = cols(idx)
  def jdbcResult = rs
  def close {
    val st = rs.getStatement
    rs.close
    if (!reusableStatement) st.close
  }

  override def toList = { val l = (this map (r => Row(this.content))).toList; close; l }

  def content = {
    val b = new scala.collection.mutable.ListBuffer[Any]
    var i = 0
    while (i < columnCount) {
      b += (this(i) match {
        case r: Result => r.toList
        case x => x
      })
      i += 1
    }
    Vector(b: _*)
  }

  /** needs to be overriden since super class implementation calls hasNext method */
  override def toString = getClass.toString + ":" + (cols.mkString(","))

  private def asAny(pos: Int): Any = {
    import java.sql.Types._
    md.getColumnType(pos) match {
      case ARRAY | BINARY | BLOB | DATALINK | DISTINCT | JAVA_OBJECT | LONGVARBINARY | NULL |
        OTHER | REF | STRUCT | VARBINARY => rs.getObject(pos)
      //scala BigDecimal is returned instead of java.math.BigDecimal
      //because it can be easily compared using standart operators (==, <, >, etc) 
      case DECIMAL | NUMERIC => {
        val bd = rs.getBigDecimal(pos); if (rs.wasNull) null else BigDecimal(bd)
      }
      case BIGINT | INTEGER | SMALLINT | TINYINT => {
        val v = rs.getLong(pos); if (rs.wasNull) null else v
      }
      case BIT | BOOLEAN => val v = rs.getBoolean(pos); if (rs.wasNull) null else v
      case VARCHAR | CHAR | CLOB | LONGVARCHAR => rs.getString(pos)
      case DATE => rs.getDate(pos)
      case TIME | TIMESTAMP => rs.getTimestamp(pos)
      case DOUBLE | FLOAT | REAL => val v = rs.getDouble(pos); if (rs.wasNull) null else v
    }
  }
  
  case class Row(row: Seq[Any]) extends RowLike {
    def apply(idx: Int) = row(idx)
    def content = row
    def columnCount = row.length
    def column(idx: Int) = Result.this.column(idx)
    override def equals(row: Any) = row match {
      case r: RowLike => this.row == r.content
      case r: Seq[_] => this.row == r
      case _ => false
    }
  }  
}

trait RowLike {
  def apply(idx: Int): Any
  def columnCount: Int
  def content: Seq[Any]
  def column(idx: Int): Column
}

case class Column(val idx: Int, val name: String, private[query] val expr: Expr)
