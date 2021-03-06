/*
 * Copyright (c) 2017 sadikovi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.sadikovi.riff.tree;

import java.io.Serializable;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.types.DataType;

import com.github.sadikovi.riff.column.ColumnFilter;

/**
 * Typed expression interface encapsulates all options that are used to resolve `BoundReference`
 * nodes. Subclasses are required to implement equals and hashCode methods.
 * All predicate related methods should be implement following this rule:
 * {value at ordinal <action> this expression}.
 */
public interface TypedExpression<E> extends Comparable<E>, Serializable {
  /**
   * Associated Spark SQL data type for this expression.
   * @return data type
   */
  DataType dataType();

  /**
   * Value at ordinal in row equals this expression.
   * @return true if row ordinal value equals expression, false otherwise
   */
  boolean eqExpr(InternalRow row, int ordinal);

  /**
   * Value at ordinal in row is greater than this expression.
   * @return true if row ordinal value is greater than expression, false otherwise
   */
  boolean gtExpr(InternalRow row, int ordinal);

  /**
   * Value at ordinal in row is less than this expression.
   * @return true if row ordinal value is less than expression, false otherwise
   */
  boolean ltExpr(InternalRow row, int ordinal);

  /**
   * Row value at ordinal in row is greater than or equal to this expression.
   * @return true if ordinal value is greater than or equal to expression, false otherwise
   */
  boolean geExpr(InternalRow row, int ordinal);

  /**
   * Row value at ordinal in row is less than or equal to this expression.
   * @return true if ordinal value is less than or equal to expression, false otherwise
   */
  boolean leExpr(InternalRow row, int ordinal);

  /**
   * Return true if typed expression is in column filter, false otherwise.
   * @param filter column filter
   * @return true if expression is in filter, false otherwise
   */
  boolean containsExpr(ColumnFilter filter);

  /**
   * Compare this typed expression with provided object.
   * Should return -1, 0, 1 as a result for less than, equals, and greater than.
   * @param obj object to compare
   * @return comparison value
   */
  int compareTo(E obj);

  /**
   * Java "equals" method to compare with an object.
   * Should be implemented against its own class.
   * @return true if this instance equals to obj, false otherwise
   */
  boolean equals(Object obj);

  /**
   * Get hash code.
   * @return hash code for this typed expression
   */
  int hashCode();

  /**
   * Return copy of this typed expression.
   * @return copy
   */
  TypedExpression copy();

  /**
   * Return string representation of this typed expression.
   * @return pretty string
   */
  String prettyString();
}
