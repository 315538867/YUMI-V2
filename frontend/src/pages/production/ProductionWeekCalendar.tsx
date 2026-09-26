import { Button } from 'antd';
import type { IncompleteReminderView, ProductionTaskView } from '../../api/production';
import { ProductionTaskCard } from './ProductionTaskCard';
import { todayString, weekDays } from './productionLogic';

export interface ProductionWeekCalendarProps {
  anchor: string;
  tasks: ProductionTaskView[];
  remindersByItemNode?: Map<string, IncompleteReminderView[]>;
  onOpenTask?: (task: ProductionTaskView) => void;
  onVerifyTask?: (task: ProductionTaskView) => void;
  onAddTask?: (date: string) => void;
}

/**
 * 日期横向周历（阶段五 5.17，对齐原型 `.week-head` + `.week-grid`）：列为周内日期（周一→周日），
 * 员工只是卡片属性与筛选条件，不把日期列改成员工主导航，也不隐藏跨员工同日任务。
 * 每列底部有蓝色「+ 安排」链接，进入统一新建表单（只导航，不写）。
 */
export function ProductionWeekCalendar({
  anchor,
  tasks,
  remindersByItemNode,
  onOpenTask,
  onVerifyTask,
  onAddTask,
}: ProductionWeekCalendarProps) {
  const days = weekDays(anchor);
  const today = todayString();
  const gridColumns = 'repeat(7, minmax(150px, 1fr))';
  return (
    <div className="calendar-card" data-testid="production-week-calendar">
      <div className="week-head" style={{ display: 'grid', gridTemplateColumns: gridColumns, minWidth: 1050 }}>
        {days.map(day => (
          <div key={day.date} className={day.date === today ? 'today' : undefined}>
            {day.weekday}
            <span>{day.label}</span>
            <small>{tasks.filter(task => task.taskDate === day.date).length} 条安排</small>
          </div>
        ))}
      </div>
      <div className="week-grid" style={{ display: 'grid', gridTemplateColumns: gridColumns, minWidth: 1050 }}>
        {days.map(day => {
          const dayTasks = tasks.filter(task => task.taskDate === day.date);
          return (
            <div
              key={day.date}
              className={day.date === today ? 'day-cell today' : 'day-cell'}
              data-testid={`week-day-${day.date}`}
            >
              <div className="day-items">
                {dayTasks.map(task => (
                  <ProductionTaskCard
                    key={task.id}
                    task={task}
                    remindersByItemNode={remindersByItemNode}
                    onOpen={onOpenTask}
                    onVerify={onVerifyTask}
                  />
                ))}
              </div>
              <Button
                type="link"
                size="small"
                className="add-day"
                data-testid={`add-day-${day.date}`}
                onClick={() => onAddTask?.(day.date)}
              >
                + 安排
              </Button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
