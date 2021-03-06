/*
* This program is free software; you can redistribute it and/or modify it under the
* terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
* Foundation.
*
* You should have received a copy of the GNU Lesser General Public License along with this
* program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
* or from the Free Software Foundation, Inc.,
* 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*
* This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
* without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
* See the GNU Lesser General Public License for more details.
*
* Copyright (c) 2001 - 2013 Object Refinery Ltd, Pentaho Corporation and Contributors..  All rights reserved.
*/

package org.pentaho.reporting.engine.classic.extensions.datasources.olap4j;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.swing.table.AbstractTableModel;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.olap4j.Cell;
import org.olap4j.CellSet;
import org.olap4j.CellSetAxis;
import org.olap4j.Position;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Member;
import org.pentaho.reporting.engine.classic.core.MetaAttributeNames;
import org.pentaho.reporting.engine.classic.core.MetaTableModel;
import org.pentaho.reporting.engine.classic.core.util.CloseableTableModel;
import org.pentaho.reporting.engine.classic.core.wizard.DataAttributes;
import org.pentaho.reporting.engine.classic.core.wizard.DefaultDataAttributes;
import org.pentaho.reporting.engine.classic.core.wizard.EmptyDataAttributes;
import org.pentaho.reporting.engine.classic.core.wizard.DefaultConceptQueryMapper;
import org.pentaho.reporting.libraries.base.util.FastStack;

/**
 * Axis indexes are predefined: 0 = Columns, 1 = rows, 2 = pages, 3 = sections, 4 = Chapters, 5 and greater: unnamed) Up
 * to 128 axises can be specified. There can be queries with no axis at all, which returns a single cell.
 *
 * @author : Thomas Morgner
 */
@SuppressWarnings({"UnnecessaryUnboxing"})
public class DenormalizedMDXTableModel extends AbstractTableModel implements CloseableTableModel, MetaTableModel
{
  private static final Log logger = LogFactory.getLog(DenormalizedMDXTableModel.class);

  private boolean noMeasures;
  private CellSet resultSet;
  private int rowCount;
  private int columnCount;
  private String[] columnNames;
  private int[] axesSize;
  private int[] columnToAxisPosition;
  private Dimension[] columnToDimensionMapping;
  private QueryResultWrapper resultWrapper;

  public DenormalizedMDXTableModel(final QueryResultWrapper resultWrapper)
  {
    if (resultWrapper == null)
    {
      throw new NullPointerException("ResultSet returned was null");
    }
    this.resultWrapper = resultWrapper;
    this.resultSet = resultWrapper.getCellSet();

    // rowcount is the product of all axis-sizes. If an axis contains more than one member, then
    // Mondrian already performs the crossjoin for us.

    // column count is the sum of the maximum of all hierachy levels of all axis.

    final List<CellSetAxis> axes = this.resultSet.getAxes();
    this.rowCount = 0;
    this.axesSize = new int[axes.size()];
    final int[] axesMembers = new int[axes.size()];
    final List<Dimension>[] dimensionsForMembersPerAxis = new List[axes.size()];
    final List<Integer>[] membersPerAxis = new List[axes.size()];

    // Axis contains (zero or more) positions, which contains (zero or more) members
    for (int axesIndex = axes.size() - 1; axesIndex >= 0; axesIndex -= 1)
    {
      final CellSetAxis axis = axes.get(axesIndex);
      final List<Position> positions = axis.getPositions();

      axesSize[axesIndex] = positions.size();
      if (positions.isEmpty())
      {
        noMeasures = true;
      }

      final ArrayList<Integer> memberList = new ArrayList<Integer>();
      final ArrayList<Dimension> dimensionsForMembers = new ArrayList<Dimension>();
      for (int positionsIndex = 0; positionsIndex < positions.size(); positionsIndex++)
      {
        final Position position = positions.get(positionsIndex);
        final List<Member> members = position.getMembers();
        for (int positionIndex = 0; positionIndex < members.size(); positionIndex++)
        {
          final LinkedHashSet<String> columnNamesSet = new LinkedHashSet<String>();
          Member m = members.get(positionIndex);
          final Dimension dimension = m.getDimension();
          while (m != null)
          {
            final String name = m.getLevel().getUniqueName();
            if (columnNamesSet.contains(name) == false)
            {
              columnNamesSet.add(name);
            }
            m = m.getParentMember();
          }

          final int hierarchyLevelCount = columnNamesSet.size();

          if (memberList.size() <= positionIndex)
          {
            memberList.add(hierarchyLevelCount);
            dimensionsForMembers.add(dimension);
          }
          else
          {
            final Integer existingLevel = memberList.get(positionIndex);
            if (existingLevel.intValue() < hierarchyLevelCount)
            {
              memberList.set(positionIndex, hierarchyLevelCount);
              dimensionsForMembers.set(positionIndex, dimension);
            }
          }
        }
      }

      int memberCount = 0;
      for (int i = 0; i < memberList.size(); i++)
      {
        memberCount += memberList.get(i);
      }
      axesMembers[axesIndex] = memberCount;
      dimensionsForMembersPerAxis[axesIndex] = dimensionsForMembers;
      membersPerAxis[axesIndex] = memberList;
    }

    if (axesSize.length > 0)
    {
      rowCount = axesSize[0];
      for (int i = 1; i < axesSize.length; i++)
      {
        final int size = axesSize[i];
        rowCount *= size;
      }
    }

    rowCount = Math.max(1, rowCount);
    for (int i = 0; i < axesMembers.length; i++)
    {
      columnCount += axesMembers[i];
    }

    if (noMeasures == false)
    {
      columnCount += 1;
    }

    columnNames = new String[columnCount];
    columnToDimensionMapping = new Dimension[columnCount];
    columnToAxisPosition = new int[columnCount];

    int columnIndex = 0;
    int dimColIndex = 0;

    final FastStack memberStack = new FastStack();
    for (int axesIndex = axes.size() - 1; axesIndex >= 0; axesIndex -= 1)
    {
      final CellSetAxis axis = axes.get(axesIndex);
      final List<Position> positions = axis.getPositions();
      final LinkedHashSet<String> columnNamesSet = new LinkedHashSet<String>();
      for (int positionsIndex = 0; positionsIndex < positions.size(); positionsIndex++)
      {
        final Position position = positions.get(positionsIndex);
        final List<Member> members = position.getMembers();
        for (int positionIndex = 0; positionIndex < members.size(); positionIndex++)
        {
          memberStack.clear();
          Member m = members.get(positionIndex);
          while (m != null)
          {
            memberStack.push(m);
            m = m.getParentMember();
          }

          while (memberStack.isEmpty() == false)
          {
            m = (Member) memberStack.pop();
            final String name = m.getLevel().getUniqueName();
            if (columnNamesSet.contains(name) == false)
            {
              columnNamesSet.add(name);
            }
          }
        }
      }

      if (columnNamesSet.size() != axesMembers[axesIndex])
      {
        logger.error("ERROR: Number of names is not equal the pre-counted number.");
      }

      final List<Dimension> dimForMemberPerAxis = dimensionsForMembersPerAxis[axesIndex];
      final List<Integer> memberCntPerAxis = membersPerAxis[axesIndex];
      for (int i = 0; i < memberCntPerAxis.size(); i++)
      {
        final Integer count = memberCntPerAxis.get(i);
        final Dimension dim = dimForMemberPerAxis.get(i);
        for (int x = 0; x < count.intValue(); x += 1)
        {
          this.columnToDimensionMapping[dimColIndex + x] = dim;
          this.columnToAxisPosition[dimColIndex + x] = axesIndex;
        }
        dimColIndex = count.intValue() + dimColIndex;
      }

      final String[] names = columnNamesSet.toArray(new String[columnNamesSet.size()]);
      System.arraycopy(names, 0, this.columnNames, columnIndex, names.length);
      columnIndex += names.length;
    }

    if (noMeasures == false)
    {
      final Member measureName = computeMeasureName(resultSet);
      if (measureName != null)
      {
        columnNames[columnIndex] = measureName.getUniqueName();
      }
      else
      {
        columnNames[columnIndex] = "Measure";
      }
    }
  }

  private static Member computeMeasureName(final CellSet resultSet)
  {
    final List<Position> positionList = resultSet.getFilterAxis().getPositions();
    for (int i = 0; i < positionList.size(); i++)
    {
      final Position position = positionList.get(i);
      final List<Member> members = position.getMembers();
      for (int positionIndex = 0; positionIndex < members.size(); positionIndex++)
      {

        Member m = members.get(positionIndex);
        while (m != null)
        {
          if (m.getMemberType() == Member.Type.MEASURE)
          {
            return m;
          }
          m = m.getParentMember();
        }
      }
    }

    return null;
  }

  public int getRowCount()
  {
    return rowCount;
  }

  public int getColumnCount()
  {
    return columnCount;
  }

  /**
   * Returns a default name for the column using spreadsheet conventions: A, B, C, ... Z, AA, AB, etc.  If
   * <code>column</code> cannot be found, returns an empty string.
   *
   * @param column the column being queried
   * @return a string containing the default name of <code>column</code>
   */
  public String getColumnName(final int column)
  {
    return columnNames[column];
  }

  public Class getColumnClass(final int columnIndex)
  {
    if (getRowCount() == 0)
    {
      return Object.class;
    }
    try
    {
      final Object targetClassObj = getValueAt(0, columnIndex);
      if (targetClassObj == null)
      {
        return Object.class;
      }
      else
      {
        return targetClassObj.getClass();
      }
    }
    catch (Exception e)
    {
      return Object.class;
    }
  }

  public Object getValueAt(final int rowIndex,
                           final int columnIndex)
  {
    if (columnIndex >= columnNames.length)
    {
      throw new IndexOutOfBoundsException();
    }

    final List<Integer> cellKey = new ArrayList(axesSize.length);
    int tmpRowIdx = rowIndex;
    for (int i = 0; i < axesSize.length; i++)
    {
      final int axisSize = axesSize[i];
      if (axisSize == 0)
      {
        cellKey.add(Integer.valueOf(0));
      }
      else
      {
        final int pos = tmpRowIdx % axisSize;
        cellKey.add(Integer.valueOf(pos));
        tmpRowIdx = tmpRowIdx / axisSize;
      }
    }

    // user asked for a dimension ...
    final Dimension dimension = columnToDimensionMapping[columnIndex];
    if (dimension == null)
    {
      final Cell cell = resultSet.getCell(cellKey);
      if (cell.isNull())
      {
        return null;
      }
      return cell.getValue();
    }

    Member contextMember = getContextMember(dimension, columnIndex, cellKey);
    final String name = contextMember.getParentMember() == null ? contextMember.getName() : null;
    while (contextMember != null)
    {
      if (contextMember.getLevel().getUniqueName().equals(getColumnName(columnIndex)))
      {
        return contextMember.getName();
      }
      contextMember = contextMember.getParentMember();
    }
    return name;
  }

  private Member getContextMember(final Dimension dimension,
                                  final int columnIndex,
                                  final List<Integer> cellKey)
  {
    final int axisIndex = columnToAxisPosition[columnIndex];
    final CellSetAxis axis = resultSet.getAxes().get(axisIndex);
    final Integer posIndex = cellKey.get(axisIndex);
    final List<Position> positionList = axis.getPositions();
    if (positionList.isEmpty())
    {
      return null;
    }

    final Position position = positionList.get(posIndex);
    final List<Member> memberList = position.getMembers();
    for (int i = 0; i < memberList.size(); i++)
    {
      final Member member = memberList.get(i);
      if (dimension.equals(member.getDimension()))
      {
        return member;
      }
    }
    return null;
  }

  public void close()
  {
    try
    {
      resultSet.close();
    }
    catch (SQLException e)
    {
      // ignore, but log.
    }
    try
    {
      final Statement statement = resultWrapper.getStatement();
      statement.close();
    }
    catch (SQLException e)
    {
      // ignore ..
    }
  }

  /**
   * Returns the meta-attribute as Java-Object. The object type that is expected by the report engine is defined in the
   * TableMetaData property set. It is the responsibility of the implementor to map the native meta-data model into a
   * model suitable for reporting.
   * <p/>
   * Meta-data models that only describe meta-data for columns can ignore the row-parameter.
   *
   * @param rowIndex    the row of the cell for which the meta-data is queried.
   * @param columnIndex the index of the column for which the meta-data is queried.
   * @return the meta-data object.
   */
  public DataAttributes getCellDataAttributes(final int rowIndex, final int columnIndex)
  {
    if (columnIndex >= columnNames.length)
    {
      throw new IndexOutOfBoundsException();
    }

    final List<Integer> cellKey = new ArrayList(axesSize.length);
    int tmpRowIdx = rowIndex;
    for (int i = 0; i < axesSize.length; i++)
    {
      final int axisSize = axesSize[i];
      if (axisSize == 0)
      {
        cellKey.add(Integer.valueOf(tmpRowIdx));
      }
      else
      {
        final int pos = tmpRowIdx % axisSize;
        cellKey.add(Integer.valueOf(pos));
        tmpRowIdx = tmpRowIdx / axisSize;
      }
    }

    // user asked for a dimension ...
    final Dimension dimension = columnToDimensionMapping[columnIndex];
    if (dimension == null || noMeasures == false)
    {
      final Cell cell = resultSet.getCell(cellKey);
      return new MDXMetaDataCellAttributes(EmptyDataAttributes.INSTANCE, cell);
    }

    Member contextMember = getContextMember(dimension, columnIndex, cellKey);
    while (contextMember != null)
    {
      if (contextMember.getLevel().getUniqueName().equals(getColumnName(columnIndex)))
      {
        return new MDXMetaDataMemberAttributes(EmptyDataAttributes.INSTANCE, contextMember);
      }
      contextMember = contextMember.getParentMember();
    }

    return EmptyDataAttributes.INSTANCE;
  }

  public boolean isCellDataAttributesSupported()
  {
    return true;
  }

  public DataAttributes getColumnAttributes(final int column)
  {
    return EmptyDataAttributes.INSTANCE;
  }

  /**
   * Returns table-wide attributes. This usually contain hints about the data-source used to query the data as well as
   * hints on the sort-order of the data.
   *
   * @return the table attributes.
   */
  public DataAttributes getTableAttributes()
  {
    final DefaultDataAttributes dataAttributes = new DefaultDataAttributes();
    dataAttributes.setMetaAttribute(MetaAttributeNames.Core.NAMESPACE,
        MetaAttributeNames.Core.CROSSTAB_MODE, DefaultConceptQueryMapper.INSTANCE,  MetaAttributeNames.Core.CROSSTAB_VALUE_NORMALIZED);
    return dataAttributes;
  }
}
