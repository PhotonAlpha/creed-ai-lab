import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, InputNumber, Modal, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useI18n } from '../../locales';
import type { TopoCombo } from './buildGraph';

/** Where one participant is drawn, as opposed to what it is connected to. */
export interface LayoutEdit {
  /** `null` hands the participant back to the layering derived from the links. */
  layer: number | null;
  sortOrder: number;
}

interface LayerEditorModalProps {
  open: boolean;
  onClose: () => void;
  /** The model's participants, already carrying both the derived layer and the stored one. */
  combos: TopoCombo[];
  releaseName: string;
  saving: boolean;
  /** Resolves `true` when the release was written; the dialog stays open on a rejection. */
  onSave: (edits: Map<number, LayoutEdit>) => Promise<boolean>;
}

/**
 * The hand-placed half of the hierarchy: pin a participant to a layer, or push an app system along
 * the cross axis. Saved into the release, so everyone who opens it sees the same picture.
 *
 * Edits are **staged and saved on demand**, not applied per keystroke. The write is
 * `PUT /releases/{id}/topology`, which replaces the release's whole topology in one transaction —
 * one request per digit typed would be both wasteful and, on a rejection, a way to leave half the
 * table applied. The graph behind the dialog therefore does not move until Save lands.
 *
 * Layers read **one-based** here and in the detail panel, because "layer 1" is what someone counting
 * columns says; the model and the database are zero-based and the translation happens only here.
 */
export function LayerEditorModal({
  open,
  onClose,
  combos,
  releaseName,
  saving,
  onSave,
}: LayerEditorModalProps) {
  const { t } = useI18n();
  const [draft, setDraft] = useState<Map<number, LayoutEdit>>(new Map());

  /*
   * Re-seeded every time the dialog opens, and only then.
   *
   * Seeding on every `combos` change would throw away what is being typed the moment the model
   * rebuilds; keying it on `open` means a cancelled edit is genuinely discarded and a saved one
   * comes back from the release.
   */
  useEffect(() => {
    if (!open) return;
    setDraft(
      new Map(
        combos.map((combo) => [
          combo.participantId,
          { layer: combo.pinned ? combo.layer : null, sortOrder: combo.order },
        ]),
      ),
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  /*
   * Sorted by app system, not by the layer being edited.
   *
   * `combos` arrives in drawing order, which would change under the cursor as soon as a layer is
   * typed. The graph is where the new order belongs; this table is a list of participants and holds
   * still while it is being edited.
   */
  const rows = useMemo(
    () =>
      combos
        .slice()
        .sort((a, b) => a.appSystem.localeCompare(b.appSystem) || a.title.localeCompare(b.title)),
    [combos],
  );

  const edit = (participantId: number, patch: Partial<LayoutEdit>) =>
    setDraft((previous) => {
      const next = new Map(previous);
      const current = next.get(participantId) ?? { layer: null, sortOrder: 0 };
      next.set(participantId, { ...current, ...patch });
      return next;
    });

  const editOf = (combo: TopoCombo): LayoutEdit =>
    draft.get(combo.participantId) ?? {
      layer: combo.pinned ? combo.layer : null,
      sortOrder: combo.order,
    };

  const dirty = combos.some((combo) => {
    const value = editOf(combo);
    return value.layer !== (combo.pinned ? combo.layer : null) || value.sortOrder !== combo.order;
  });
  const pinned = combos.filter((combo) => editOf(combo).layer !== null).length;
  const cleared = combos.every((combo) => {
    const value = editOf(combo);
    return value.layer === null && value.sortOrder === 0;
  });

  const columns: ColumnsType<TopoCombo> = [
    {
      title: t('topology.custom.participant'),
      dataIndex: 'title',
      render: (_value, combo) => (
        <Space size={6}>
          <Typography.Text>{combo.title}</Typography.Text>
          <Tag bordered={false}>{combo.appSystem}</Tag>
        </Space>
      ),
    },
    {
      title: t('topology.custom.derived'),
      dataIndex: 'derivedLayer',
      width: 110,
      align: 'center',
      render: (_value, combo) => (
        <Typography.Text type="secondary">{combo.derivedLayer + 1}</Typography.Text>
      ),
    },
    {
      title: t('topology.custom.layer'),
      width: 130,
      align: 'center',
      render: (_value, combo) => {
        const value = editOf(combo).layer;
        return (
          <InputNumber
            size="small"
            min={1}
            max={100}
            style={{ width: '100%' }}
            placeholder={t('topology.custom.auto')}
            value={value === null ? null : value + 1}
            onChange={(next) =>
              edit(combo.participantId, {
                layer: typeof next === 'number' ? Math.round(next) - 1 : null,
              })
            }
          />
        );
      },
    },
    {
      title: t('topology.custom.order'),
      width: 120,
      align: 'center',
      render: (_value, combo) => {
        const value = editOf(combo).sortOrder;
        return (
          <InputNumber
            size="small"
            min={-999}
            max={999}
            style={{ width: '100%' }}
            placeholder="0"
            value={value === 0 ? null : value}
            onChange={(next) =>
              edit(combo.participantId, {
                sortOrder: typeof next === 'number' ? Math.round(next) : 0,
              })
            }
          />
        );
      },
    },
  ];

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={t('topology.custom.title')}
      width={780}
      maskClosable={!dirty}
      footer={
        <Space>
          <Button
            danger
            disabled={cleared || saving}
            onClick={() =>
              setDraft(
                new Map(combos.map((combo) => [combo.participantId, { layer: null, sortOrder: 0 }])),
              )
            }
          >
            {t('topology.custom.reset')}
          </Button>
          <Button onClick={onClose} disabled={saving}>
            {t('common.cancel')}
          </Button>
          <Button
            type="primary"
            loading={saving}
            disabled={!dirty}
            onClick={() => {
              void onSave(draft).then((saved) => {
                if (saved) onClose();
              });
            }}
          >
            {t('common.save')}
          </Button>
        </Space>
      }
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message={t('topology.custom.desc')}
          description={t('topology.custom.stored', { release: releaseName, count: pinned })}
        />
        <Table<TopoCombo>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={rows}
          pagination={false}
          scroll={{ y: 360 }}
        />
      </Space>
    </Modal>
  );
}
