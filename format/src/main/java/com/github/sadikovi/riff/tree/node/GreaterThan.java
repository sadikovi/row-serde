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

package com.github.sadikovi.riff.tree.node;

import org.apache.spark.sql.catalyst.InternalRow;

import com.github.sadikovi.riff.column.ColumnFilter;
import com.github.sadikovi.riff.stats.Statistics;
import com.github.sadikovi.riff.tree.Rule;
import com.github.sadikovi.riff.tree.Tree;
import com.github.sadikovi.riff.tree.TypedBoundReference;
import com.github.sadikovi.riff.tree.TypedExpression;

/**
 * [[GreaterThan]] is inequality predicate for typed expression.
 * Ordinal row value is greater than expression value.
 */
public class GreaterThan extends TypedBoundReference {
  private final String name;
  private final TypedExpression expr;

  public GreaterThan(String name, TypedExpression expr) {
    this.name = name;
    this.expr = expr;
  }

  @Override
  public TypedExpression expression() {
    return this.expr;
  }

  @Override
  public String operator() {
    return ">";
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean evaluateState(InternalRow row, int ordinal) {
    return !row.isNullAt(ordinal) && expr.gtExpr(row, ordinal);
  }

  @Override
  public boolean evaluateState(Statistics stats) {
    return !stats.isNullAt(Statistics.ORD_MAX) && expr.gtExpr(stats, Statistics.ORD_MAX);
  }

  @Override
  public boolean evaluateState(ColumnFilter filter) {
    // column filter is not evaluated for GreaterThan
    return true;
  }

  @Override
  public Tree transform(Rule rule) {
    return rule.update(this);
  }

  @Override
  public Tree copy() {
    return new GreaterThan(name, expr.copy()).copyOrdinal(this);
  }
}
